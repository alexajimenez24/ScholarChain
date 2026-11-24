package scholarchain.storage

import scala.collection.mutable

/** A bounded FIFO of embedding work. `offer` returns false when the queue is full, so a producer
  * must wait (drain) before adding more: back-pressure instead of unbounded memory.
  *
  * Why a bounded in-process queue and a manifest-driven batch (not SQS) in this phase: the loader
  * is a replayable batch whose unit of work is a vector key, and every step is idempotent (stable
  * keys, conditional writes, hash-and-model skip), so a replay after a crash needs no at-least-once
  * broker; the Spark load manifest (`s3vectors.json`) names the inputs and their row counts. An SQS
  * adapter would need a queue, a dead-letter queue and IAM from infra-engineer and costs money;
  * this phase makes no cloud call. The same trait can be implemented over SQS by the Phase 10
  * embedding worker without changing the loader.
  */
trait WorkQueue[A]:
  def capacity: Int
  def size: Int
  def offer(item: A): Boolean
  def drain(max: Int): Vector[A]
  final def isFull: Boolean = size >= capacity
  final def isEmpty: Boolean = size == 0

final class InMemoryWorkQueue[A](val capacity: Int) extends WorkQueue[A]:
  require(capacity >= 1, "queue capacity must be at least 1")
  private val items = mutable.Queue.empty[A]
  private var peak = 0

  def size: Int = items.size
  def highWater: Int = peak

  def offer(item: A): Boolean =
    if items.size >= capacity then false
    else
      items.enqueue(item)
      peak = math.max(peak, items.size)
      true

  def drain(max: Int): Vector[A] =
    val n = math.min(max, items.size)
    Vector.fill(n)(items.dequeue())
