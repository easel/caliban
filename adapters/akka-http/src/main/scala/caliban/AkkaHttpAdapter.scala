package caliban

import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.stream.{ Materializer, OverflowStrategy }
import caliban.execution.QueryExecution
import caliban.interop.tapir.TapirAdapter.zioMonadError
import caliban.interop.tapir.{ RequestInterceptor, TapirAdapter, WebSocketHooks }
import sttp.capabilities.WebSockets
import sttp.capabilities.akka.AkkaStreams
import sttp.capabilities.akka.AkkaStreams.Pipe
import sttp.model.{ Part, StatusCode }
import sttp.tapir.Codec.JsonCodec
import sttp.tapir.Endpoint
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.akkahttp.AkkaHttpServerInterpreter
import zio._
import zio.duration._
import zio.random.Random
import zio.stream.ZStream

import scala.concurrent.{ ExecutionContext, Future }

object AkkaHttpAdapter {

  def makeHttpService[R, E](
    interpreter: GraphQLInterpreter[R, E],
    skipValidation: Boolean = false,
    enableIntrospection: Boolean = true,
    queryExecution: QueryExecution = QueryExecution.Parallel,
    requestInterceptor: RequestInterceptor[R] = RequestInterceptor.empty
  )(implicit
    runtime: Runtime[R],
    requestCodec: JsonCodec[GraphQLRequest],
    responseCodec: JsonCodec[GraphQLResponse[E]]
  ): Route = {
    val endpoints = TapirAdapter.makeHttpService[R, E](
      interpreter,
      skipValidation,
      enableIntrospection,
      queryExecution,
      requestInterceptor
    )
    AkkaHttpServerInterpreter().toRoute(
      endpoints.map(endpoint =>
        ServerEndpoint[(GraphQLRequest, ServerRequest), StatusCode, GraphQLResponse[E], Any, Future](
          endpoint.endpoint,
          _ => req => runtime.unsafeRunToFuture(endpoint.logic(zioMonadError)(req)).future
        )
      )
    )
  }

  def makeHttpUploadService[R, E](
    interpreter: GraphQLInterpreter[R, E],
    skipValidation: Boolean = false,
    enableIntrospection: Boolean = true,
    queryExecution: QueryExecution = QueryExecution.Parallel,
    requestInterceptor: RequestInterceptor[R] = RequestInterceptor.empty
  )(implicit
    runtime: Runtime[R with Random],
    requestCodec: JsonCodec[GraphQLRequest],
    mapCodec: JsonCodec[Map[String, Seq[String]]],
    responseCodec: JsonCodec[GraphQLResponse[E]]
  ): Route = {
    val endpoint = TapirAdapter.makeHttpUploadService[R, E](
      interpreter,
      skipValidation,
      enableIntrospection,
      queryExecution,
      requestInterceptor
    )
    AkkaHttpServerInterpreter().toRoute(
      ServerEndpoint[(Seq[Part[Array[Byte]]], ServerRequest), StatusCode, GraphQLResponse[E], Any, Future](
        endpoint.endpoint,
        _ => req => runtime.unsafeRunToFuture(endpoint.logic(zioMonadError)(req)).future
      )
    )
  }

  def makeWebSocketService[R, E](
    interpreter: GraphQLInterpreter[R, E],
    skipValidation: Boolean = false,
    enableIntrospection: Boolean = true,
    keepAliveTime: Option[Duration] = None,
    queryExecution: QueryExecution = QueryExecution.Parallel,
    requestInterceptor: RequestInterceptor[R] = RequestInterceptor.empty,
    webSocketHooks: WebSocketHooks[R, E] = WebSocketHooks.empty
  )(implicit
    ec: ExecutionContext,
    runtime: Runtime[R],
    materializer: Materializer,
    inputCodec: JsonCodec[GraphQLWSInput],
    outputCodec: JsonCodec[GraphQLWSOutput]
  ): Route = {

    val endpoint = TapirAdapter.makeWebSocketService[R, E](
      interpreter,
      skipValidation,
      enableIntrospection,
      keepAliveTime,
      queryExecution,
      requestInterceptor,
      webSocketHooks
    )
    AkkaHttpServerInterpreter().toRoute(
      ServerEndpoint[ServerRequest, StatusCode, Pipe[
        GraphQLWSInput,
        GraphQLWSOutput
      ], AkkaStreams with WebSockets, Future](
        endpoint.endpoint.asInstanceOf[Endpoint[ServerRequest, StatusCode, Pipe[GraphQLWSInput, GraphQLWSOutput], Any]],
        _ =>
          req =>
            runtime
              .unsafeRunToFuture(endpoint.logic(zioMonadError)(req))
              .future
              .map(_.map { zioPipe =>
                val io =
                  for {
                    inputQueue     <- ZQueue.unbounded[GraphQLWSInput]
                    input           = ZStream.fromQueue(inputQueue)
                    output          = zioPipe(input)
                    sink            = Sink.foreachAsync[GraphQLWSInput](1)(input =>
                                        runtime.unsafeRunToFuture(inputQueue.offer(input).unit).future
                                      )
                    (queue, source) = Source.queue[GraphQLWSOutput](0, OverflowStrategy.fail).preMaterialize()
                    fiber          <- output.foreach(msg => ZIO.fromFuture(_ => queue.offer(msg))).forkDaemon
                    flow            = Flow.fromSinkAndSourceCoupled(sink, source).watchTermination() { (_, f) =>
                                        f.onComplete(_ => runtime.unsafeRun(fiber.interrupt))
                                      }
                  } yield flow
                runtime.unsafeRun(io)
              })
      )
    )
  }
}
