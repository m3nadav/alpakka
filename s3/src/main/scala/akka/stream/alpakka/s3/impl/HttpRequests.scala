/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.alpakka.s3.impl

import akka.stream.alpakka.s3.S3Settings

import scala.concurrent.{ ExecutionContext, Future }
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.{ Host, RawHeader }
import akka.util.ByteString
import akka.stream.alpakka.s3.acl.CannedAcl
import akka.stream.scaladsl.Source
import akka.http.scaladsl.model.RequestEntity

private[alpakka] object HttpRequests {

  def s3Request(s3Location: S3Location,
                region: String,
                method: HttpMethod = HttpMethods.GET,
                uriFn: (Uri => Uri) = identity)(implicit conf: S3Settings): HttpRequest =
    HttpRequest(method)
      .withHeaders(Host(requestHost(s3Location, region)))
      .withUri(uriFn(requestUri(s3Location, region)))

  def initiateMultipartUploadRequest(s3Location: S3Location,
                                     contentType: ContentType,
                                     cannedAcl: CannedAcl,
                                     region: String)(implicit conf: S3Settings): HttpRequest =
    s3Request(s3Location, region, HttpMethods.POST, _.withQuery(Query("uploads")))
      .withDefaultHeaders(RawHeader("x-amz-acl", cannedAcl.value))
      .withEntity(HttpEntity.empty(contentType))

  def getRequest(s3Location: S3Location, region: String)(implicit conf: S3Settings): HttpRequest =
    s3Request(s3Location, region)

  def uploadPartRequest(upload: MultipartUpload,
                        partNumber: Int,
                        payload: Source[ByteString, _],
                        payloadSize: Int,
                        region: String)(implicit conf: S3Settings): HttpRequest =
    s3Request(
      upload.s3Location,
      region,
      HttpMethods.PUT,
      _.withQuery(Query("partNumber" -> partNumber.toString, "uploadId" -> upload.uploadId))
    ).withEntity(HttpEntity(ContentTypes.`application/octet-stream`, payloadSize, payload))

  def completeMultipartUploadRequest(upload: MultipartUpload, parts: Seq[(Int, String)], region: String)(
      implicit ec: ExecutionContext,
      conf: S3Settings): Future[HttpRequest] = {

    val payload = <CompleteMultipartUpload>
                    {
                      parts.map { case (partNumber, etag) => <Part><PartNumber>{ partNumber }</PartNumber><ETag>{ etag }</ETag></Part> }
                    }
                  </CompleteMultipartUpload>
    for {
      entity <- Marshal(payload).to[RequestEntity]
    } yield {
      s3Request(
        upload.s3Location,
        region,
        HttpMethods.POST,
        _.withQuery(Query("uploadId" -> upload.uploadId))
      ).withEntity(entity)
    }
  }

  def requestHost(s3Location: S3Location, region: String)(implicit conf: S3Settings): Uri.Host =
    conf.proxy match {
      case None => Uri.Host(s"${s3Location.bucket}.s3-${region}.amazonaws.com")
      case Some(proxy) => Uri.Host(proxy.host)
    }

  def requestUri(s3Location: S3Location, region: String)(implicit conf: S3Settings): Uri = {
    val uri = Uri(s"/${s3Location.key}").withHost(requestHost(s3Location, region)).withScheme("https")
    conf.proxy match {
      case None => uri
      case Some(proxy) => uri.withPort(proxy.port)
    }
  }

}
