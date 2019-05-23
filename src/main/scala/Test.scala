import io.lemonlabs.uri.Uri
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util
import java.util.Date
import java.util.logging.Logger
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import org.xbill.DNS.DClass
import org.xbill.DNS.ExtendedResolver
import org.xbill.DNS.Lookup
import org.xbill.DNS.Message
import org.xbill.DNS.Record
import org.xbill.DNS.Resolver
import org.xbill.DNS.ReverseMap
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type
import scala.io.Source
import scala.io.StdIn.readLine

object Test {

  val logger = Logger.getGlobal

  val maxPrintBodyChars = sys.env.get("MAX_BODY_CHARS").map(_.toInt).getOrElse(100)
  val disableRedirects = sys.env.get("DISABLE_REDIRECTS").exists(_.toBoolean)

  var mayBeUrls = sys.env.get("TEST_URLS").orElse(Some("https://stackoverflow.com"))


  def main(args: Array[String]): Unit = {

    val SetUrl = "seturl"
    val Hit = "hit"
    val DumpCache = "dump"
    val FlushCache = "flush"
    val Resolve = "resolve"

    val help = s"Valid commands are $SetUrl | $Hit | $DumpCache | $FlushCache | $Resolve: "

    while(true) {
      val cmd : String = readLine(help)
      cmd match {
        case SetUrl =>
          mayBeUrls = Some(readLine("Enter endpoint to use (csv for multiple endpoints) : "))
        case DumpCache =>
          printDnsCache()
        case FlushCache =>
          clearDnsCache()
        case Hit =>
          assert(mayBeUrls.nonEmpty)
          hit(mayBeUrls.get)
        case Resolve =>
          val customNs = readLine("(optional) enter a name server : ")
          val domain = readLine("enter a domain : ")
          resolve(customNs, domain)
        case "exit" =>
          println("exiting...")
          sys.exit(0)
        case x => println(s"Unrecognized cmd : $x")
      }
    }
  }

  def hit(urls: String) = {
    urls.split(",").foreach { url =>
      def run(f: => Any) = try {
        Thread.sleep(100)
        f
      } catch {
        case e: Throwable => e.printStackTrace()
      }

      val u = Uri.parse(url)
      val host = u.toUrl.hostOption.get.toString()
      run(javaNativeConnection(url))
      run(parsedNativeConnection(url))
      run(apacheHttpClient(url))
      run(inet(host))
    }
  }

  def clearDnsCache(): Unit = {
    try {
      val acf = classOf[InetAddress].getDeclaredField("addressCache")
      acf.setAccessible(true)
      val o = acf.get(null)
      val cf = o.getClass.getDeclaredField("cache")
      cf.setAccessible(true)
      cf.get(o).asInstanceOf[util.HashMap[String, Any]].clear()
      logger.info("cleared dns cache")
    } catch {
      case e: Throwable => e.printStackTrace()
    }
  }

  def printDnsCache(): Unit = {
    val addressCache = "addressCache"
    logger.info(s"$addressCache --------->")
    printDnsCache(addressCache)
    val negativeCache = "negativeCache"
    logger.info(s"$negativeCache --------->")
    printDnsCache(negativeCache)
  }

  def printDnsCache(cacheName: String): Unit = {
    val acf = classOf[InetAddress].getDeclaredField(cacheName)
    acf.setAccessible(true)
    val addressCache = acf.get(null)
    val cf = addressCache.getClass.getDeclaredField("cache")
    cf.setAccessible(true)
    import scala.collection.JavaConverters._
    val map = cf.get(addressCache).asInstanceOf[util.LinkedHashMap[String, Any]].asScala
    map.foreach { case (k, v) =>
      v.getClass.getDeclaredFields.foreach(x => logger.config(s"$x"))
      val expF = v.getClass.getDeclaredField("expiration")
      expF.setAccessible(true)
      val expires = expF.get(v).asInstanceOf[Long]

      val addrF = v.getClass.getDeclaredField("addresses")
      addrF.setAccessible(true)
      logger.info(s"$k expires at ${new Date(expires)} for ${addrF.get(v).asInstanceOf[Array[InetAddress]].map(_.getHostAddress).mkString(",")}")
    }
  }

  def inet(url: String) {
    var address = InetAddress.getLocalHost
    logger.info(s"local address : $address")
    address = InetAddress.getByName(url)
    logger.info(s"named address : $address")
    val SW = InetAddress.getAllByName(url)
    SW.foreach(i => logger.info(s"$i InetAddress.getCanonicalHostName() ${i.getCanonicalHostName}"))
  }


  def toRecord(ip: String) = {
    import org.xbill.DNS.ExtendedResolver
    val res = new ExtendedResolver()

    val name = ReverseMap.fromAddress(ip)
    val `type` = Type.PTR
    val dclass = DClass.IN
    val rec = Record.newRecord(name, `type`, dclass)
    val query = Message.newQuery(rec)
    val response = res.send(query)
    val answers = response.getSectionArray(org.xbill.DNS.Section.ANSWER)
    answers.foreach(x => logger.info(s" -> $x"))
  }

  def resolve(ns : String, domain: String) = {
    assert(!domain.isEmpty)
    if (!ns.isEmpty) {
      val dr = Lookup.getDefaultResolver
      val r = new SimpleResolver(ns)
      Lookup.setDefaultResolver(new ExtendedResolver(Array[Resolver](r, dr)))
    }

    val recordTypes = List(Type.A, Type.SRV)

    recordTypes.foreach { t =>
      val l = new Lookup(domain, t)
      val result = l.run()
      if (result == null) {
        logger.warning(s"no records of type $t found for $domain using $ns")
      } else {
        result.foreach(x => logger.info(s"record $x found for $domain"))
      }
    }
  }

  def javaNativeConnection(url: String): Unit = {
    processHttpConn(new URL(url).openConnection().asInstanceOf[HttpURLConnection])
  }

  def parsedNativeConnection(url: String): Unit = {
    processHttpConn(Uri.parse(url).toJavaURI.toURL.openConnection().asInstanceOf[HttpURLConnection])
  }

  def apacheHttpClient(url: String): Unit = {
    val client = HttpClientBuilder.create.build()
    val is = client.execute(new HttpGet(url)).getEntity.getContent
    val body = Source.fromInputStream(is).mkString.take(maxPrintBodyChars)
    logger.info(s"body starts with : \n$body\n...")
  }

  def processHttpConn(conn: HttpURLConnection) : Unit = {
    if (disableRedirects) {
      logger.info("redirect disabled")
      conn.setInstanceFollowRedirects(false)
    } else {
      logger.info("redirect enabled")
      conn.setInstanceFollowRedirects(true)
    }
    val body = Source.fromInputStream(conn.getInputStream).mkString.take(maxPrintBodyChars)
    logger.info(s"body starts with : \n$body\n...")
  }
}
