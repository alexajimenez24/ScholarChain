package scholarchain.hadoop

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.util.ToolRunner

/** Runs the whole pipeline on this JVM with Hadoop's LocalJobRunner and the local file system: the
  * bounded local pipeline for samples and tests. The same [[Pipeline]] runs on EMR; only the
  * framework setting differs. It needs the Hadoop client runtime on the classpath (Test scope in
  * the build), for example:
  *
  * `sbt "hadoop/Test/runMain scholarchain.hadoop.LocalRunner --dblp <xml> --output <dir> --snapshot
  * 2026-11-22"`
  *
  * Hadoop's scratch space goes to `<output>/_local-tmp`, never to a shared `/tmp` path.
  */
object LocalRunner:
  def localConf(output: String): Configuration =
    val c = new Configuration()
    c.set("mapreduce.framework.name", "local")
    c.set("fs.defaultFS", "file:///")
    c.set("hadoop.tmp.dir", s"${output.stripSuffix("/")}/_local-tmp")
    c

  def main(args: Array[String]): Unit =
    val output = args.sliding(2).collectFirst { case Array("--output", v) => v }.getOrElse(".")
    val code = ToolRunner.run(localConf(output), new Pipeline, args)
    sys.exit(code)
