package it.dtk.reactive.jobs

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, Supervision }
import com.sksamuel.elastic4s._
import com.sksamuel.elastic4s.streams.ReactiveElastic._
import com.sksamuel.elastic4s.streams.ScrollPublisher
import com.softwaremill.react.kafka.ReactiveKafka
import com.typesafe.config.ConfigFactory
import it.dtk.es.ElasticQueryTerms
import it.dtk.model._
import helpers._
import net.ceedubs.ficus.Ficus._
import org.joda.time.DateTime
import org.json4s.NoTypeHints
import org.json4s.ext.JodaTimeSerializers
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization

import scala.language.implicitConversions

/**
 * Created by fabiofumarola on 08/03/16.
 */
class QueryTermsToNews(configFile: String, kafka: ReactiveKafka)(implicit
  val system: ActorSystem,
    implicit val mat: ActorMaterializer) {

  val config = ConfigFactory.load(configFile).getConfig("reactive_wtl")

  //Elasticsearch Params
  val esHosts = config.as[String]("elastic.hosts")
  val termsDocPath = config.as[String]("elastic.docs.query_terms")
  val clusterName = config.as[String]("elastic.clusterName")
  val hostname = config.as[String]("hostname")

  val feedsIndexPath = config.as[String]("elastic.docs.feeds")
  val batchSize = config.as[Int]("elastic.feeds.batch_size")

  //Kafka Params
  val kafkaBrokers = config.as[String]("kafka.brokers")
  val topic = config.as[String]("kafka.topics.feeds")

  val client = new ElasticQueryTerms(esHosts, termsDocPath, clusterName).client

  def run(): Unit = {

    val articles = queryTermSource()
      .flatMapConcat(q => terms.generateUrls(q.terms, q.lang, hostname))
      .flatMapConcat(u => terms.getResultsAsArticles(u))
      .map(gander.mainContent)

    saveArticlesToKafkaProtobuf(articles, kafka, kafkaBrokers, topic)

    val feeds = articles
      .map(a => a.publisher -> html.host(a.uri))
      .filter(_._2.nonEmpty)
      .map(f => (f._1, f._2.get))
      .filterNot(_._2.contains("comment"))
      .flatMapConcat {
        case (newsPublisher, url) =>
          val rss = html.findRss(url)
          Source(rss.filterNot(_.contains("comment")).map(url =>
            Feed(url, newsPublisher, List.empty, Some(DateTime.now()))))
      }

    saveToElastic(feeds, client, feedsIndexPath, batchSize, 2)
    feeds.runWith(Sink.foreach(println))
  }

  implicit val formats = Serialization.formats(NoTypeHints) ++ JodaTimeSerializers.all

  def queryTermSource(): Source[QueryTerm, NotUsed] = {

    implicit object QueryTermsHitAs extends HitAs[QueryTerm] {
      override def as(hit: RichSearchHit): QueryTerm = {
        parse(hit.getSourceAsString).extract[QueryTerm]
      }
    }

    val publisher: ScrollPublisher = client.publisher(termsDocPath, keepAlive = "180m")

    val toCheckQueries = Source.fromPublisher(publisher)
      .map(res => res.as[QueryTerm])
      .filter(_.timestamp.getOrElse(DateTime.now.minusMinutes(10)).isBeforeNow)

    toCheckQueries
  }

  def extractArticles(hostname: String) = Flow[QueryTerm]
    .flatMapConcat(q => terms.generateUrls(q.terms, q.lang, hostname))
    .flatMapConcat(u => terms.getResultsAsArticles(u))
    .map(gander.mainContent)

}
