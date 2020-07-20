package com.wixpress.dst.greyhound.core.consumer.retry

import com.wixpress.dst.greyhound.core.consumer.domain.{ConsumerRecord, ConsumerSubscription, RecordHandler}
import com.wixpress.dst.greyhound.core.metrics.GreyhoundMetrics
import com.wixpress.dst.greyhound.core.producer.Producer
import zio._
import zio.blocking.Blocking
import zio.clock.Clock

object RetryRecordHandler {
  /**
    * Return a handler with added retry behavior based on the provided `RetryPolicy`.
    * Upon failures,
    * 1. if non-blocking policy is chosen the `producer` will be used to send the failing records to designated
    * retry topics where the handling will be retried, after an optional delay. This
    * allows the handler to keep processing records in the original topic - however,
    * ordering will be lost for retried records!
    * 2. if blocking policy is chosen, the handling of the same message will be retried according to provided intervals
    * ordering is guaranteed
    * 3. if both policies are chosen, the blocking policy will be invoked first, and only if it fails the non-blocking policy will be invoked
    */
  def withRetries[R2, R, E, K, V](handler: RecordHandler[R, E, K, V],
                                  retryConfig: RetryConfig, producer: Producer,
                                  subscription: ConsumerSubscription,
                                  blockingState: Ref[Map[BlockingTarget, BlockingState]],
                                  nonBlockingRetryHelper: NonBlockingRetryHelper)
                                 (implicit evK: K <:< Chunk[Byte], evV: V <:< Chunk[Byte]): RecordHandler[R with R2 with Clock with Blocking with GreyhoundMetrics, Nothing, K, V] = {

    val nonBlockingHandler = NonBlockingRetryRecordHandler(handler, producer, subscription, evK, evV, nonBlockingRetryHelper)
    val blockingHandler = BlockingRetryRecordHandler(handler, retryConfig, blockingState, nonBlockingHandler)
    val blockingAndNonBlockingHandler = BlockingAndNonBlockingRetryRecordHandler(blockingHandler, nonBlockingHandler)

    (record: ConsumerRecord[K, V]) => {
      retryConfig.retryType match {
        case BlockingFollowedByNonBlocking => blockingAndNonBlockingHandler.handle(record)
        case NonBlocking => nonBlockingHandler.handle(record)
        case Blocking => blockingHandler.handle(record)
      }
    }
  }
}

object ZIOHelper {
  def foreachWhile[R, E, A](as: Iterable[A])(f: A => ZIO[R, E, LastHandleResult]): ZIO[R, E, LastHandleResult] =
    ZIO.effectTotal(as.iterator).flatMap { i =>
      def loop: ZIO[R, E, LastHandleResult] =
        if (i.hasNext) f(i.next).flatMap(result => if(result.shouldContinue) loop else (UIO(result)))
        else UIO(LastHandleResult(lastHandleSucceeded = false, shouldContinue = false))
      loop
    }
}

case class LastHandleResult(lastHandleSucceeded: Boolean, shouldContinue: Boolean)