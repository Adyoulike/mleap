package ml.combust.mleap.executor.service

import akka.actor.{Actor, Props, Status}
import akka.pattern.pipe
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy, QueueOfferResult}
import ml.combust.mleap.executor._
import ml.combust.mleap.runtime.frame.{Row, RowTransformer, Transformer}

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

object RowStreamActor {

  def props(transformer: Transformer,
            request: CreateRowStreamRequest)
           (implicit materializer: Materializer): Props = {
    Props(new RowStreamActor(transformer, request))
  }

  object Messages {
    case object Initialize
    case class TransformRow(row: StreamTransformRowRequest, tag: Any)
    case object StreamClosed
  }
}

class RowStreamActor(transformer: Transformer,
                     request: CreateRowStreamRequest)
                    (implicit materializer: Materializer) extends Actor {
  import RowStreamActor.Messages
  import context.dispatcher

  val rowStream: RowStream = RowStream(request.modelName,
    request.streamName,
    request.streamConfig,
    request.spec)

  val rowTransformer: Try[RowTransformer] = transformer.transform(RowTransformer(request.spec.schema)).flatMap {
    rt =>
      request.spec.options.select.map {
        s =>
          request.spec.options.selectMode match {
            case SelectMode.Strict => rt.select(s: _*)
            case SelectMode.Relaxed => Try(rt.relaxedSelect(s: _*))
          }
      }.getOrElse(Try(rt))
  }

  private var queue: Option[SourceQueueWithComplete[(Messages.TransformRow, Promise[(Try[Option[Row]], Any)])]] = None
  private var queueF: Option[Future[SourceQueueWithComplete[(Messages.TransformRow, Promise[(Try[Option[Row]], Any)])]]] = None


  override def postStop(): Unit = {
    for (q <- queue) { q.complete() }
  }

  override def receive: Receive = {
    case r: Messages.TransformRow => transformRow(r)
    case Messages.Initialize => initialize()
    case Messages.StreamClosed => context.stop(self)

    case r: CreateRowFlowRequest => createRowFlow(r)

    case Status.Failure(err) => throw err
  }

  def initialize(): Unit = {
    rowTransformer match {
      case Success(rt) =>
        if (queue.isEmpty) {
          queue = Some {
            val source = Source.queue[(Messages.TransformRow, Promise[(Try[Option[Row]], Any)])](rowStream.streamConfig.bufferSize, OverflowStrategy.backpressure)
            val transform = Flow[(Messages.TransformRow, Promise[(Try[Option[Row]], Any)])].mapAsyncUnordered(rowStream.streamConfig.parallelism) {
              case (Messages.TransformRow(tRow, tag), promise) =>
                Future {
                  val row = Try(rt.transformOption(tRow.row))

                  (row, tag, promise)
                }
            }.idleTimeout(rowStream.streamConfig.idleTimeout).to(Sink.foreach {
              case (row, tag, promise) => promise.success((row, tag))
            })

            source.toMat(transform)(Keep.left).run()
          }

          queue.get.watchCompletion().
            map(_ => Messages.StreamClosed).
            pipeTo(self)

          queueF = Some(Future(queue.get))
        }

        sender ! rowStream
      case Failure(err) => sender ! Status.Failure(err)
    }
  }

  def transformRow(row: Messages.TransformRow): Unit = {
    val promise: Promise[(Try[Option[Row]], Any)] = Promise()
    val s = sender

    queueF = Some(queueF.get.flatMap {
      q =>
        q.offer((row, promise)).map {
          case QueueOfferResult.Enqueued =>
            promise.future.pipeTo(s)
            q
          case QueueOfferResult.Failure(err) =>
            promise.failure(err)
            q
          case QueueOfferResult.Dropped =>
            promise.failure(new IllegalStateException("item dropped"))
            q
          case QueueOfferResult.QueueClosed =>
            promise.failure(new IllegalStateException("queue closed"))
            q
        }
    })
  }

  def createRowFlow(request: CreateRowFlowRequest): Unit = {
    queue match {
      case Some(q) =>
        rowTransformer match {
          case Success(rt) => sender ! (self, rt, q.watchCompletion())
          case Failure(err) => sender ! Status.Failure(err)
        }
      case None => sender ! Status.Failure(new IllegalStateException(s"row stream not initialized ${rowStream.modelName}/row/${rowStream.streamName}"))
    }
  }
}
