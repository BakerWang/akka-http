/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http

import com.sun.jersey.spi.container.servlet.ServletContainer

/**
 * This is just a simple wrapper on top of ServletContainer to inject some akka.config from the akka.conf
 * If you were using akka.comet.AkkaServlet before, but only use it for Jersey, you should switch to this servlet instead
 */
class AkkaRestServlet extends ServletContainer {
  import akka.config.Config.{ config ⇒ c }

  val initParams = new java.util.HashMap[String, String]

  addInitParameter("com.sun.jersey.akka.config.property.packages", c.getList("akka.http.resource-packages").mkString(";"))
  addInitParameter("com.sun.jersey.spi.container.ResourceFilters", c.getList("akka.http.filters").mkString(","))

  /**
   * Provide a fallback for default values
   */
  override def getInitParameter(key: String) =
    Option(super.getInitParameter(key)).getOrElse(initParams get key)

  /**
   * Provide a fallback for default values
   */
  override def getInitParameterNames() = {
    import scala.collection.JavaConversions._
    initParams.keySet.iterator ++ super.getInitParameterNames
  }

  /**
   * Provide possibility to add akka.config params
   */
  def addInitParameter(param: String, value: String): Unit = initParams.put(param, value)
}
