package io.bernhardt.akka

import akka.actor.ActorSystem
import akka.http.scaladsl.model.DateTime
import akka.stream.ActorMaterializer
import play.api.libs.ws.WSAuthScheme
import play.api.libs.ws.ahc.StandaloneAhcWSClient

object Reporting {

  def email(content: String, system: ActorSystem) = {
    val ws = StandaloneAhcWSClient()(ActorMaterializer()(system))
    val hostname = system.settings.config.getString("akka.remote.netty.tcp.hostname")

    for {
      mailgunDomain <- Option(system.settings.config.getString("reporting.mailgun.domain"))
      mailgunKey <- Option(system.settings.config.getString("reporting.mailgun.key"))
      to <- Option(system.settings.config.getString("reporting.email.to"))
    } yield {
      ws.url(s"https://api.mailgun.net/v3/$mailgunDomain/messages")
        .withAuth("api", mailgunKey, WSAuthScheme.BASIC)
        .post(Map(
          "from" -> s"postmaster@$mailgunDomain",
          "to" -> to,
          "subject" -> s"Akka FD Benchmark results $hostname ${DateTime.now.toString()}",
          "text" -> content
        ))
    }
  }
}