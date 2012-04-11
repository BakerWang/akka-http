/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import akka.migration._

import javax.servlet.http.{HttpServletResponse, HttpServletRequest}
import javax.servlet.http.HttpServlet
import javax.servlet.Filter
import akka.config.OldConfig
import akka.actor.{OldActor, GlobalActorSystem, ActorRef, Actor}
import akka.event.OldEventHandler

/**
 * @author Garrick Evans
 */
object MistSettings {

  val JettyServer = "jetty"
  val TimeoutAttribute = "timeout"

  val ConnectionClose = OldConfig.config.getBool("akka.http.connection-close", true)
  val RootActorBuiltin = OldConfig.config.getBool("akka.http.root-actor-builtin", true)
  val RootActorPath = OldConfig.config.getString("akka.http.root-actor-path", "/http/root")
  val DefaultTimeout = OldConfig.config.getLong("akka.http.timeout", 1000)
  val ExpiredHeaderName = OldConfig.config.getString("akka.http.expired-header-name", "Async-Timeout")
  val ExpiredHeaderValue = OldConfig.config.getString("akka.http.expired-header-value", "expired")
}

/**
 * Structural type alias's required to work with both Servlet 3.0 and Jetty's Continuation API
 *
 * @author Garrick Evans
 */
object Types {

  import javax.servlet.{ServletRequest, ServletResponse}

  /**
   * Represents an asynchronous request
   */
  type tAsyncRequest = {
    def startAsync: tAsyncRequestContext
  }

  /**
   * Used to match both AsyncContext and AsyncContinuation in order to complete the request
   */
  type tAsyncRequestContext = {
    def complete
    def getRequest: ServletRequest
    def getResponse: ServletResponse
  }

  type Header = (String, String)
  type Headers = List[Header]

  def Headers(): Headers = Nil
}

import Types._

/**
 *
 */
trait Mist {

  import javax.servlet.ServletContext
  import MistSettings._

  /**
   * The root endpoint actor
   */
  protected val _root = GlobalActorSystem.actorFor(RootActorPath)

  /**
   * Server-specific method factory
   */
  protected var _factory: Option[RequestMethodFactory] = None

  /**
   * Handles all servlet requests
   */
  protected def mistify(request: HttpServletRequest,
                        response: HttpServletResponse)(builder: (() ⇒ tAsyncRequestContext) ⇒ RequestMethod) = {
    def suspend: tAsyncRequestContext = {

      // set to right now, which is effectively "already expired"
      response.setDateHeader("Expires", System.currentTimeMillis)
      response.setHeader("Cache-Control", "no-cache, must-revalidate")

      // no keep-alive?
      if (ConnectionClose) response.setHeader("Connection", "close")

      // suspend the request
      // TODO: move this out to the specialized support if jetty asyncstart doesnt let us update TOs
      request.asInstanceOf[tAsyncRequest].startAsync.asInstanceOf[tAsyncRequestContext]
    }

    // shoot the message to the root endpoint for processing
    // IMPORTANT: the suspend method is invoked on the server thread not in the actor
    val method = builder(suspend _)
    if (method.go) _root ! method
  }

  /**
   * Sets up what mist needs to be able to service requests
   * must be called prior to dispatching to "mistify"
   */
  def initMist(context: ServletContext) {
    _factory = if (context.getMajorVersion >= 3) Some(Servlet30ContextMethodFactory)
    else throw new IllegalStateException("Akka Mist requires at least servlet 3.0")
  }
}

/**
 * AkkaMistServlet adds support to bridge Http and Actors in an asynchronous fashion
 * Async impls currently supported: Servlet3.0, Jetty Continuations
 */
class AkkaMistServlet extends HttpServlet with Mist {

  import javax.servlet.ServletConfig

  /**
   * Initializes Mist
   */
  override def init(config: ServletConfig) {
    super.init(config)
    initMist(config.getServletContext)
  }

  protected override def doDelete(req: HttpServletRequest, res: HttpServletResponse) = mistify(req, res)(_factory.get.Delete)

  protected override def doGet(req: HttpServletRequest, res: HttpServletResponse) = mistify(req, res)(_factory.get.Get)

  protected override def doHead(req: HttpServletRequest, res: HttpServletResponse) = mistify(req, res)(_factory.get.Head)

  protected override def doOptions(req: HttpServletRequest, res: HttpServletResponse) = mistify(req, res)(_factory.get.Options)

  protected override def doPost(req: HttpServletRequest, res: HttpServletResponse) = mistify(req, res)(_factory.get.Post)

  protected override def doPut(req: HttpServletRequest, res: HttpServletResponse) = mistify(req, res)(_factory.get.Put)

  protected override def doTrace(req: HttpServletRequest, res: HttpServletResponse) = mistify(req, res)(_factory.get.Trace)
}

/**
 * Proof-of-concept, use at own risk
 * Will be officially supported in a later release
 */
class AkkaMistFilter extends Filter with Mist {

  import javax.servlet.{ServletRequest, ServletResponse, FilterConfig, FilterChain}

  /**
   * Initializes Mist
   */
  def init(config: FilterConfig) {
    initMist(config.getServletContext)
  }

  /**
   * Decide how/if to handle the request
   */
  override def doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) {
    (req, res) match {
      case (hreq: HttpServletRequest, hres: HttpServletResponse) ⇒
        hreq.getMethod.toUpperCase match {
          case "DELETE" ⇒ mistify(hreq, hres)(_factory.get.Delete)
          case "GET" ⇒ mistify(hreq, hres)(_factory.get.Get)
          case "HEAD" ⇒ mistify(hreq, hres)(_factory.get.Head)
          case "OPTIONS" ⇒ mistify(hreq, hres)(_factory.get.Options)
          case "POST" ⇒ mistify(hreq, hres)(_factory.get.Post)
          case "PUT" ⇒ mistify(hreq, hres)(_factory.get.Put)
          case "TRACE" ⇒ mistify(hreq, hres)(_factory.get.Trace)
          case unknown ⇒ {}
        }
        chain.doFilter(req, res)
      case _ ⇒ chain.doFilter(req, res)
    }
  }

  override def destroy {}
}

///////////////////////////////////////////
//  Endpoints
///////////////////////////////////////////

object Endpoint {

  import akka.dispatch.Dispatchers

  /**
   * leverage the akka akka.OldConfig to tweak the dispatcher for our endpoints
   */
  //TODO
//  val Dispatcher = Dispatchers.fromOldConfig("akka.http.mist-dispatcher")

  type Provider = PartialFunction[String, ActorRef]

  case class Attach(provider: Provider)

  case class NoneAvailable(req: RequestMethod)

}

/**
 * @author Garrick Evans
 */
trait Endpoint {
  this: Actor ⇒

  import Endpoint._


  /*
  * val myActor = system.actorOf(Props[MyActor].withDispatcher("my-dispatcher"), "myactor")
  * */
// TODO
//  self.dispatcher = Endpoint.Dispatcher

  /**
   * A convenience method to get the actor ref
   */
  def actor: ActorRef = this.self

  /**
   * The list of connected endpoints to which this one should/could forward the request.
   * The message will be sent to the actor defined for uri.
   */
  protected def provide: Provider

  //
  // we expect there to be one root and that it's already been started up
  // obviously there are plenty of other ways to obtaining this actor
  //  the point is that we need to attach something (for starters anyway)
  //  to the root
  //
  def parentEndpoint: ActorRef = GlobalActorSystem.actorFor(MistSettings.RootActorPath)

  //
  // this is where you want attach your endpoint hooks
  //
  override def preStart() {
    parentEndpoint ! Endpoint.Attach {
      case uri if provide.isDefinedAt(uri) => self
    }
  }


  /**
   * no endpoint available - completes the request with a 404
   */
  /*protected def noEndpointAvailable(req: RequestMethod) = sender match {
    case Some(sender) => sender reply NoneAvailable(req)
    case None => req.NotFound("No endpoint available for [" + req.request.getPathInfo + "]")
  }*/
  protected def noEndpointAvailable(req: RequestMethod) = {
    req.NotFound("No endpoint available for [" + req.request.getPathInfo + "]")
  }


  protected def dispatchHttpRequest: Receive = {
    case req: RequestMethod =>
      val uri = req.request.getPathInfo
      println(">>> " + uri)
      provide.lift(uri) match {
        case Some(endpoint) => endpoint ! req
        case None ⇒ noEndpointAvailable(req)
      }
  }
}

class RootEndpoint extends OldActor with Endpoint {

  import Endpoint._
  import MistSettings._

  // adopt the OldConfigured id
  //TODO
//  if (RootActorBuiltin) self.path.toString = RootActorPath

  protected def attach(provider: Provider) {
    _providers = _providers orElse provider
  }


  override def preStart() {}

  protected var _providers: Provider = Map()

  protected def provide = _providers

  protected def recv: Receive = {
    case NoneAvailable(req) ⇒ noEndpointAvailable(req)

    // add the endpoint - the if the uri hook matches,
    // the message will be sent to the actor returned by the provider func
    case Attach(provider) ⇒ attach(provider)
  }

  protected def receive: Receive = recv orElse dispatchHttpRequest
}


///////////////////////////////////////////
//  RequestMethods
///////////////////////////////////////////

/**
 * Basic description of the suspended async http request.
 * Must be mixed with some kind of specific support (e.g. servlet 3.0 or jetty continuations)
 *
 * @author Garrick Evans
 */
trait RequestMethod {

  import java.io.IOException
  import javax.servlet.http.{HttpServletResponse, HttpServletRequest}

  // required implementations
  val builder: () ⇒ tAsyncRequestContext

  /**
   * Provides a general type for the underlying context
   *
   * @return a completable request context
   */
  val context: Option[tAsyncRequestContext]

  def go: Boolean

  /**
   * Updates (resets) the timeout
   *
   * @return true if updated, false if not supported
   */
  def timeout(ms: Long): Boolean

  /**
   * Status of the suspension
   */
  def suspended: Boolean

  //
  // convenience funcs
  //

  def request = context.get.getRequest.asInstanceOf[HttpServletRequest]

  def response = context.get.getResponse.asInstanceOf[HttpServletResponse]

  def getHeaderOrElse(name: String, default: Function[Any, String]): String =
    request.getHeader(name) match {
      case null ⇒ default(null)
      case s ⇒ s
    }

  def getParameterOrElse(name: String, default: Function[Any, String]): String =
    request.getParameter(name) match {
      case null ⇒ default(null)
      case s ⇒ s
    }

  def complete(status: Int, body: String): Boolean = complete(status, body, Headers())

  def complete(status: Int, body: String, headers: Headers): Boolean =
    rawComplete {
      res ⇒
        res.setStatus(status)
        headers foreach {
          h ⇒ response.setHeader(h._1, h._2)
        }
        res.getWriter.write(body)
        res.getWriter.close
        res.flushBuffer
    }

  def rawComplete(completion: HttpServletResponse ⇒ Unit): Boolean =
    context match {
      case Some(pipe) ⇒
        try {
          if (!suspended) false
          else {
            completion(response)
            pipe.complete
            true
          }
        } catch {
          case io: Exception ⇒
            OldEventHandler.error(io, this, io.getMessage)
            false
        }
      case None ⇒ false
    }

  def complete(t: Throwable) {
    context match {
      case Some(pipe) ⇒
        try {
          if (suspended) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to write data to connection on resume")
            pipe.complete
          }
        } catch {
          case io: IOException ⇒
            OldEventHandler.error(io, this, io.getMessage)
        }
      case None ⇒ {}
    }
  }

  /*
   * Utility methods to send responses back
   */
  def OK(body: String): Boolean = complete(HttpServletResponse.SC_OK, body)

  def OK(body: String, headers: Headers): Boolean = complete(HttpServletResponse.SC_OK, body, headers)

  def Created(body: String): Boolean = complete(HttpServletResponse.SC_CREATED, body)

  def Accepted(body: String): Boolean = complete(HttpServletResponse.SC_ACCEPTED, body)

  def NotModified(body: String): Boolean = complete(HttpServletResponse.SC_NOT_MODIFIED, body)

  def BadRequest(body: String): Boolean = complete(HttpServletResponse.SC_BAD_REQUEST, body)

  def Unauthorized(body: String): Boolean = complete(HttpServletResponse.SC_UNAUTHORIZED, body)

  def Forbidden(body: String): Boolean = complete(HttpServletResponse.SC_FORBIDDEN, body)

  def NotAllowed(body: String): Boolean = complete(HttpServletResponse.SC_METHOD_NOT_ALLOWED, body)

  def NotFound(body: String): Boolean = complete(HttpServletResponse.SC_NOT_FOUND, body)

  def Timeout(body: String): Boolean = complete(HttpServletResponse.SC_REQUEST_TIMEOUT, body)

  def Conflict(body: String): Boolean = complete(HttpServletResponse.SC_CONFLICT, body)

  def UnsupportedMediaType(body: String): Boolean = complete(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, body)

  def Error(body: String): Boolean = complete(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, body)

  def NotImplemented(body: String): Boolean = complete(HttpServletResponse.SC_NOT_IMPLEMENTED, body)

  def Unavailable(body: String, retry: Int): Boolean = complete(HttpServletResponse.SC_SERVICE_UNAVAILABLE, body, List(("Retry-After", retry.toString)))
}

abstract class Delete(val builder: () ⇒ tAsyncRequestContext) extends RequestMethod

abstract class Get(val builder: () ⇒ tAsyncRequestContext) extends RequestMethod

abstract class Head(val builder: () ⇒ tAsyncRequestContext) extends RequestMethod

abstract class Options(val builder: () ⇒ tAsyncRequestContext) extends RequestMethod

abstract class Post(val builder: () ⇒ tAsyncRequestContext) extends RequestMethod

abstract class Put(val builder: () ⇒ tAsyncRequestContext) extends RequestMethod

abstract class Trace(val builder: () ⇒ tAsyncRequestContext) extends RequestMethod

trait RequestMethodFactory {

  def Delete(f: () ⇒ tAsyncRequestContext): RequestMethod

  def Get(f: () ⇒ tAsyncRequestContext): RequestMethod

  def Head(f: () ⇒ tAsyncRequestContext): RequestMethod

  def Options(f: () ⇒ tAsyncRequestContext): RequestMethod

  def Post(f: () ⇒ tAsyncRequestContext): RequestMethod

  def Put(f: () ⇒ tAsyncRequestContext): RequestMethod

  def Trace(f: () ⇒ tAsyncRequestContext): RequestMethod
}