package com.gilt.aws.lambda

import java.io.RandomAccessFile
import java.nio.ByteBuffer

import com.amazonaws.regions.RegionUtils
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import com.amazonaws.services.lambda.AWSLambdaClient
import com.amazonaws.services.lambda.model._
import sbt._

import scala.util.{Failure, Success, Try}

private[lambda] object AwsLambda {

  def updateLambdaWithFunctionCodeRequest(region: Region, lambdaName: LambdaName,
                                          updateFunctionCodeRequest: UpdateFunctionCodeRequest): Try[UpdateFunctionCodeResult] = {
    try {
      val client = new AWSLambdaClient(AwsCredentials.provider)
      client.setRegion(RegionUtils.getRegion(region.value))

      val updateResult = client.updateFunctionCode(updateFunctionCodeRequest)

      println(s"Updated lambda ${updateResult.getFunctionArn}")
      Success(updateResult)
    }
    catch {
      case ex @ (_ : AmazonClientException |
                 _ : AmazonServiceException) =>
        Failure(ex)
    }
  }

  def createUpdateFunctionCodeRequestFromS3(resolvedBucketId: S3BucketId, s3Key: S3Key,
                                            resolvedLambdaName: LambdaName): UpdateFunctionCodeRequest = {
    val updateFunctionCodeRequest = {
      val r = new UpdateFunctionCodeRequest()
      r.setFunctionName(resolvedLambdaName.value)
      r.setS3Bucket(resolvedBucketId.value)
      r.setS3Key(s3Key.value)
      r
    }
    updateFunctionCodeRequest
  }

  def createUpdateFunctionCodeRequestFromJar(jar: File, resolvedLambdaName: LambdaName): UpdateFunctionCodeRequest = {
    val r = new UpdateFunctionCodeRequest()
    r.setFunctionName(resolvedLambdaName.value)
    val buffer = getJarBuffer(jar)
    r.setZipFile(buffer)
    r
  }

  def createLambdaWithFunctionCode(region: Region,
                   jar: File,
                   functionName: LambdaName,
                   handlerName: HandlerName,
                   roleName: RoleARN,
                   timeout:  Option[Timeout],
                   memory: Option[Memory],
                   functionCode: FunctionCode
                  ): Try[CreateFunctionResult] = {
    try {
      val client = new AWSLambdaClient(AwsCredentials.provider)
      client.setRegion(RegionUtils.getRegion(region.value))

      val request = {
        val r = new CreateFunctionRequest()
        r.setFunctionName(functionName.value)
        r.setHandler(handlerName.value)
        r.setRole(roleName.value)
        r.setRuntime(com.amazonaws.services.lambda.model.Runtime.Java8)
        if (timeout.isDefined) r.setTimeout(timeout.get.value)
        if (memory.isDefined) r.setMemorySize(memory.get.value)
        r.setCode(functionCode)

        r
      }

      val createResult = client.createFunction(request)

      println(s"Created Lambda: ${createResult.getFunctionArn}")
      Success(createResult)
    }
    catch {
      case ex@(_: AmazonClientException |
               _: AmazonServiceException) =>
        Failure(ex)
    }
  }

  def createFunctionCodeFromS3(jar: File, resolvedBucketId: S3BucketId): FunctionCode = {
    val c = new FunctionCode
    c.setS3Bucket(resolvedBucketId.value)
    c.setS3Key(jar.getName)
    c
  }

  def createFunctionCodeFromJar(jar: File): FunctionCode = {
    val c = new FunctionCode
    val buffer = getJarBuffer(jar)
    c.setZipFile(buffer)
    c
  }

  def getJarBuffer(jar: File): ByteBuffer = {
    val buffer = ByteBuffer.allocate(jar.length().toInt)
    val aFile = new RandomAccessFile(jar, "r")
    val inChannel = aFile.getChannel()
    while (inChannel.read(buffer) > 0) {}
    inChannel.close()
    buffer.rewind()
    buffer
  }
}
