package scholarchain.hadoop

import org.apache.hadoop.fs.{FSDataInputStream, Path}
import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.io.compress.CompressionCodecFactory
import org.apache.hadoop.mapreduce.{InputSplit, JobContext, RecordReader, TaskAttemptContext}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit}
import java.io.{BufferedInputStream, ByteArrayOutputStream, InputStream}
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, Charset, CodingErrorAction, StandardCharsets}
import scala.collection.mutable

/** Input format that yields one complete DBLP top-level record per value.
  *
  * Split rule: a record belongs to the split in which its start tag (`<article `, ...) begins. The
  * reader of that split keeps reading past the split end until the matching close tag, or until the
  * next record start tag when the record is not closed (DBLP records never nest, so that record is
  * Truncated and the next one is read normally). The reader of the next split skips forward to the
  * first start tag at or after its own start. So no record is split across mappers or read twice,
  * and an unclosed record never swallows the records after it. Compressed inputs (`.gz`) are not
  * splittable and are read whole by one mapper.
  *
  * Encoding: the file is decoded in the encoding declared in the XML prolog (DBLP declares
  * ISO-8859-1; UTF-8 when none is declared). Bytes invalid in that encoding reject the record
  * (BadEncoding). An encoding that is not ASCII-compatible single-byte or UTF-8 (UTF-16, UTF-32,
  * multi-byte legacy encodings), an unknown name, or a UTF-8 BOM beside another declaration rejects
  * the file as one UnsupportedEncoding unit. The charset is read once per file, from byte 0, never
  * per split.
  *
  * A unit that cannot be a record is not dropped: the reader hands it to the mapper with a negative
  * key `-(offset + 1)` and a value `STATUS:<head of the text>`, so the mapper rejects it with a
  * counter (see [[RecordStatus]]).
  */
class DblpInputFormat extends FileInputFormat[LongWritable, Text]:
  override def createRecordReader(
      split: InputSplit,
      ctx: TaskAttemptContext
  ): RecordReader[LongWritable, Text] =
    new DblpRecordReader

  override protected def isSplitable(ctx: JobContext, file: Path): Boolean =
    new CompressionCodecFactory(ctx.getConfiguration).getCodec(file) == null

enum RecordStatus:
  /** A complete record, parsed by the mapper. */
  case Complete

  /** Bigger than the record size cap: malformed input, not data. */
  case Oversize

  /** No matching close tag before end of input, or another record started inside it. */
  case Truncated

  /** The bytes are not valid in the declared encoding. */
  case BadEncoding

  /** The declared encoding cannot be scanned or is unknown; the whole file is refused. */
  case UnsupportedEncoding

/** One unit read from the input: byte offset of its start tag, status and text (the head only for a
  * non-complete record).
  */
final case class ScannedRecord(offset: Long, status: RecordStatus, text: String)

object DblpRecordScanner:
  val DefaultMaxRecordBytes: Int = 4 * 1024 * 1024
  private[hadoop] val TagNames: Set[String] = DblpParser.AllTypes
  val RejectHeadChars: Int = 2000

  /** The encoding named in the XML prolog, or UTF-8; Left carries the refusal reason. */
  def detectCharset(head: Array[Byte]): Either[String, Charset] =
    def u(i: Int) = if i < head.length then head(i) & 0xff else -1
    val bom8 = u(0) == 0xef && u(1) == 0xbb && u(2) == 0xbf
    val wide =
      (u(0) == 0xff && u(1) == 0xfe) || (u(0) == 0xfe && u(1) == 0xff) ||
        (u(0) == 0 && u(1) == 0x3c) || (u(0) == 0x3c && u(1) == 0) || (u(0) == 0 && u(1) == 0 &&
          u(2) == 0xfe && u(3) == 0xff)
    if wide then Left("UTF-16 or UTF-32 input is not supported (not ASCII-compatible)")
    else
      val text = new String(
        head,
        if bom8 then 3 else 0,
        head.length - (if bom8 then 3 else 0),
        StandardCharsets.ISO_8859_1
      )
      val prolog = "^<\\?xml\\s[^>]*\\?>".r.findFirstIn(text)
      val declared = prolog.flatMap(p =>
        "encoding\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')".r
          .findFirstMatchIn(p)
          .map(m => Option(m.group(1)).getOrElse(m.group(2)))
      )
      declared match
        case None       => Right(StandardCharsets.UTF_8)
        case Some(name) =>
          try
            val cs = Charset.forName(name)
            if bom8 && cs != StandardCharsets.UTF_8 then
              Left(s"UTF-8 byte order mark with declared encoding '$name'")
            else if cs == StandardCharsets.UTF_8 then Right(cs)
            else if cs.newEncoder().maxBytesPerChar() != 1.0f || !asciiCompatible(cs) then
              Left(s"declared encoding '$name' is not a single-byte ASCII-compatible encoding")
            else Right(cs)
          catch case _: IllegalArgumentException => Left(s"unknown declared encoding '$name'")

  private def asciiCompatible(cs: Charset): Boolean =
    val probe = "<article key=\"x\">/".getBytes(StandardCharsets.US_ASCII)
    new String(probe, cs).getBytes(StandardCharsets.US_ASCII).sameElements(probe)

  /** Bytes read from the start of a file to find the prolog. */
  val PrologBytes: Int = 1024

/** Byte scanner over a stream positioned at `start`; `end` is the split end (exclusive). ASCII
  * markup bytes never occur inside UTF-8 multi-byte sequences, so byte scanning is safe.
  */
final class DblpRecordScanner(
    in: InputStream,
    start: Long,
    end: Long,
    maxRecordBytes: Int = DblpRecordScanner.DefaultMaxRecordBytes,
    charset: Charset = StandardCharsets.UTF_8
):
  import DblpRecordScanner.*
  private val stream = new BufferedInputStream(in, 1 << 16)
  private var pos = start
  private var carry = false // a '<' of a record start tag was consumed while ending a record

  def position: Long = pos

  private def read(): Int =
    val b = stream.read()
    if b >= 0 then pos += 1
    b

  /** The next unit whose start tag begins before `end`. */
  def next(): Option[ScannedRecord] = scanForward()

  private def scanForward(): Option[ScannedRecord] =
    var result: Option[ScannedRecord] = None
    var done = false
    while !done do
      val b = if carry then { carry = false; '<'.toInt }
      else read()
      if b < 0 then done = true
      else if b == '<' then
        val tagStart = pos - 1
        if tagStart >= end then done = true
        else
          matchTag() match
            case Some(openTag) => result = Some(readRecord(tagStart, openTag)); done = true
            case None          => ()
    result

  /** Reads a tag name after '<'; returns it only when it is a record type followed by ' ' or '>'.
    */
  private def matchTag(): Option[String] =
    stream.mark(32)
    val name = new StringBuilder
    var c = stream.read()
    while c >= 'a' && c <= 'z' && name.length < 20 do
      name.append(c.toChar); c = stream.read()
    val n = name.toString
    val delimited = c == ' ' || c == '>'
    if delimited && TagNames.contains(n) then
      pos += n.length + 1
      Some(n + c.toChar.toString)
    else
      stream.reset()
      None

  /** True when the bytes after an already consumed '<' spell a record start tag; consumes nothing.
    */
  private def startTagFollows(): Boolean =
    stream.mark(32)
    val name = new StringBuilder
    var c = stream.read()
    while c >= 'a' && c <= 'z' && name.length < 20 do
      name.append(c.toChar); c = stream.read()
    stream.reset()
    (c == ' ' || c == '>') && TagNames.contains(name.toString)

  /** Reads until `</name>`, or until the next record start tag (the record is then Truncated and
    * the tag is left for the next call). Bytes beyond the size cap are skipped, not buffered, so a
    * giant record cannot exhaust memory; the end of input before the close tag is truncated too.
    */
  private def readRecord(tagStart: Long, openTag: String): ScannedRecord =
    val name = openTag.dropRight(1)
    val close = s"</$name>".getBytes(StandardCharsets.US_ASCII)
    val buf = new ByteArrayOutputStream(4096)
    buf.write('<'); buf.write(openTag.getBytes(StandardCharsets.US_ASCII))
    var overflow = false
    var matched = 0
    var nested = false
    var b = read()
    while b >= 0 && matched < close.length && !nested do
      if b == '<' && startTagFollows() then
        nested = true; carry = true
      else
        if buf.size < maxRecordBytes then buf.write(b) else overflow = true
        matched = if b == close(matched) then matched + 1 else if b == close(0) then 1 else 0
        if matched < close.length then b = read()
    val bytes = buf.toByteArray
    def head = new String(bytes, charset).take(RejectHeadChars) + s"\n[charset=${charset.name}]"
    def plainHead = new String(bytes, charset).take(RejectHeadChars)
    if overflow then ScannedRecord(tagStart, RecordStatus.Oversize, plainHead)
    else if matched < close.length then ScannedRecord(tagStart, RecordStatus.Truncated, plainHead)
    else
      strict(bytes) match
        case Some(text) => ScannedRecord(tagStart, RecordStatus.Complete, text)
        case None       => ScannedRecord(tagStart, RecordStatus.BadEncoding, head)

  private def strict(bytes: Array[Byte]): Option[String] =
    try
      Some(
        charset
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
      )
    catch case _: CharacterCodingException => None

object DblpRecordReader:
  val MaxRecordBytesKey = "scholarchain.dblp.max.record.bytes"

  /** The mapper-side encoding of one scanned unit: key and value as the reader emits them. */
  def encode(r: ScannedRecord): (Long, String) = r.status match
    case RecordStatus.Complete => (r.offset, r.text)
    case other                 => (-(r.offset + 1), s"$other:${r.text}")

  /** The file's charset, read once from the first bytes of the file whatever the split start. */
  def charsetOf(
      conf: org.apache.hadoop.conf.Configuration,
      file: Path,
      codec: org.apache.hadoop.io.compress.CompressionCodec
  ): Either[String, Charset] =
    val raw = file.getFileSystem(conf).open(file)
    val in = if codec == null then raw else codec.createInputStream(raw)
    try DblpRecordScanner.detectCharset(in.readNBytes(DblpRecordScanner.PrologBytes))
    finally in.close()

  /** Inverse of [[encode]] for a negative key: the status name and the head text. */
  def decodeRejected(value: String): (String, String) =
    val i = value.indexOf(':')
    if i < 0 then ("Truncated", value) else (value.substring(0, i), value.substring(i + 1))

class DblpRecordReader extends RecordReader[LongWritable, Text]:
  private var scanner: DblpRecordScanner = scala.compiletime.uninitialized
  private var refusal: Option[String] = None
  private var refused = false
  private var input: InputStream = scala.compiletime.uninitialized
  private var start = 0L
  private var end = 0L
  private val key = new LongWritable
  private val value = new Text

  override def initialize(split: InputSplit, ctx: TaskAttemptContext): Unit =
    val fs = split.asInstanceOf[FileSplit]
    val conf = ctx.getConfiguration
    val file = fs.getPath
    val codec = new CompressionCodecFactory(conf).getCodec(file)
    val raw: FSDataInputStream = file.getFileSystem(conf).open(file)
    if codec != null then
      input = codec.createInputStream(raw); start = 0; end = Long.MaxValue
    else
      start = fs.getStart; end = fs.getStart + fs.getLength; raw.seek(start); input = raw
    val cap = conf.getInt(
      DblpRecordReader.MaxRecordBytesKey,
      DblpRecordScanner.DefaultMaxRecordBytes
    )
    refusal = DblpRecordReader.charsetOf(conf, file, codec) match
      case Right(cs) =>
        scanner = new DblpRecordScanner(input, start, end, cap, cs); None
      case Left(reason) => Some(reason)

  override def nextKeyValue(): Boolean =
    if refusal.nonEmpty then
      // one unit for the whole file, read by the split that holds byte 0
      val r = refusal.get; refusal = None; refused = true
      if start == 0 then
        val (k, v) = DblpRecordReader.encode(ScannedRecord(0, RecordStatus.UnsupportedEncoding, r))
        key.set(k); value.set(v); true
      else false
    else if refused then false
    else
      scanner.next() match
        case Some(r) =>
          val (k, v) = DblpRecordReader.encode(r)
          key.set(k); value.set(v); true
        case None => false

  override def getCurrentKey: LongWritable = key
  override def getCurrentValue: Text = value
  override def getProgress: Float =
    if scanner == null || end == Long.MaxValue || end == start then 0f
    else math.min(1f, (scanner.position - start).toFloat / (end - start))
  override def close(): Unit = if input != null then input.close()
