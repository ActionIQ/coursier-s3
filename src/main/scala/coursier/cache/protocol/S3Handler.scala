package coursier.cache.protocol

import java.io.InputStream
import java.net.{URL, URLConnection, URLStreamHandler, URLStreamHandlerFactory}
import java.nio.charset.CodingErrorAction
import java.nio.file.{Path, Paths}

import awscala.Credentials
import awscala.s3.{Bucket, S3, S3Object}
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.regions.DefaultAwsRegionProviderChain
import com.amazonaws.services.s3.model.GetObjectRequest

import scala.collection.breakOut
import scala.io.{Codec, Source}
import scala.util.{Properties, Try}
import scala.util.control.NonFatal

/*
 * Our handler only supports one kind of URL:
 * s3://s3-<region>.amazonaws.com/<bucket-name>
 *
 * For now the region in the url is being ignored.
 *
 * It does not support credentials in the URLs for security reasons.
 *
 * You should provide credentials in one of the following places:
 * 1. In a "artifacts" AWS profile
 * 2. Anywhere in the default AWS chain: https://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/DefaultAWSCredentialsProviderChain.html
 * 3. In a`.s3credentials` file at $HOME, $HOME/.sbt, $HOME/.coursier
 *
 * You should provide region in one of the following places:
 * 1. Anywhere in the default AWS chain: https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/regions/providers/DefaultAwsRegionProviderChain.html
 * 2. In a`.s3credentials` file at $HOME, $HOME/.sbt, $HOME/.coursier
 */
class S3HandlerNotFactory extends URLStreamHandler {

  override def openConnection(url: URL): URLConnection = {
    new URLConnection(url) {
      override def getInputStream: InputStream = {
        getClient.map { s3Client =>
          val subPaths = url.getPath.stripPrefix("/").split("/")

          // Bucket
          val bucketName = subPaths.head

          // Key
          val key = subPaths.tail.mkString("/")

          val bucket = Bucket(bucketName)

          try {
            S3Object(bucket, s3Client.getObject(new GetObjectRequest(bucket.name, key))).content
          } catch {
            case e: Throwable =>
              e.printStackTrace()
              throw e
          }
        }
      }.getOrElse {
        throw new Exception("Failed to retrieve credentials")
      }

      override def connect() {}

    }
  }

  private lazy val getClient: Option[S3] = {
    val s3Client = readFromArtifactsProfile
      .orElse(readfromAwsChain)
      .orElse(readFromFile(Paths.get("").toAbsolutePath))
      .orElse(readFromFile(Paths.get(Properties.userHome)))
      .orElse(readFromFile(Paths.get(Properties.userHome).resolve(".sbt")))
      .orElse(readFromFile(Paths.get(Properties.userHome).resolve(".coursier")))

    if (s3Client.isEmpty) {
        println("No credentials found!")
    }
    s3Client
  }

  private def readFromArtifactsProfile: Option[S3] = Try {
    val regionProv = new DefaultAwsRegionProviderChain()
    val credProv = new ProfileCredentialsProvider("artifacts")

    val creds = S3(Credentials(
      credProv.getCredentials.getAWSAccessKeyId,
      credProv.getCredentials.getAWSSecretKey
    ))(awscala.Region(regionProv.getRegion))

    println("Found creds from 'artifacts' profile")
    creds
  }.toOption

  private def readfromAwsChain: Option[S3] = Try {
    val regionProv = new DefaultAwsRegionProviderChain()
    val credProv = new DefaultAWSCredentialsProviderChain()

    val creds = S3(Credentials(
      credProv.getCredentials.getAWSAccessKeyId,
      credProv.getCredentials.getAWSSecretKey
    ))(awscala.Region(regionProv.getRegion))

    println("Found creds from default profile")
    creds
  }.toOption

  private def readFromFile(path: Path): Option[S3] = {
    val file = path.resolve(".s3credentials").toFile

    implicit val codec = Codec("UTF-8")
    codec.onMalformedInput(CodingErrorAction.REPLACE)
    codec.onUnmappableCharacter(CodingErrorAction.REPLACE)

    val sourceOpt = Try(Source.fromFile(file)).toOption

    try {
      sourceOpt.flatMap { f =>
        val cleanLines = f.getLines().toList
          .map(_.trim)
          .filter(l => l.nonEmpty && !l.startsWith("#"))

        val credentials: Map[String, String] =
          cleanLines.flatMap { l =>
            val values = l.split("=").map(_.trim)
            for {
              key <- values.lift(0)
              value <- values.lift(1)
            } yield key -> value
          }(breakOut)

        for {
          accessKey <- credentials.get("accessKey")
          secretKey <- credentials.get("secretKey")
        } yield {
          val region = credentials.get("region")
            .map(awscala.Region.apply)
            .getOrElse(awscala.Region.EU_WEST_1)
          val creds = S3(Credentials(accessKey, secretKey))(region)
          println(s"Found creds from $path")
          creds
        }
      }
    } catch {
      case NonFatal(e) =>
        None
    } finally {
      sourceOpt.foreach(_.close())
    }
  }
}

class S3Handler extends URLStreamHandlerFactory {
  def createURLStreamHandler(protocol: String): URLStreamHandler = protocol match {
    case "s3" => new S3HandlerNotFactory()
    case _ => null
  }
}
