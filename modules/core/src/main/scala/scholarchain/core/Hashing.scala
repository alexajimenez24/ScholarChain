package scholarchain.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Content hashing used by every stable identifier and evidence hash. */
object Hashing:
  def sha256Hex(text: String): String = sha256Hex(text.getBytes(StandardCharsets.UTF_8))

  def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  /** A prefix of the SHA-256 hex digest; 20 hex chars = 80 bits, enough for corpus-scale IDs. */
  def short(text: String, length: Int = 20): String = sha256Hex(text).take(length)

  /** `sha256:` prefixed digest, the form stored in provenance and vector metadata. */
  def tagged(text: String): String = "sha256:" + sha256Hex(text)
