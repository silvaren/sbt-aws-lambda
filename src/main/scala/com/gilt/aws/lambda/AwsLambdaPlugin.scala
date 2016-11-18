package com.gilt.aws.lambda

import java.io.RandomAccessFile
import java.nio.ByteBuffer

import com.amazonaws.services.lambda.model.{FunctionCode, UpdateFunctionCodeRequest}
import sbt._

import scala.util.{Failure, Success}

object AwsLambdaPlugin extends AutoPlugin {

  object autoImport {
    val createLambda = taskKey[Map[String, LambdaARN]]("Create a new AWS Lambda function from the current project")
    val updateLambda = taskKey[Map[String, LambdaARN]]("Package and deploy the current project to an existing AWS Lambda")

    val s3Bucket = settingKey[Option[String]]("ID of the S3 bucket where the jar will be uploaded")
    val s3KeyPrefix = settingKey[String]("The prefix to the S3 key where the jar will be uploaded")
    val lambdaName = settingKey[Option[String]]("Name of the AWS Lambda to update")
    val handlerName = settingKey[Option[String]]("Name of the handler to be executed by AWS Lambda")
    val roleArn = settingKey[Option[String]]("ARN of the IAM role for the Lambda function")
    val region = settingKey[Option[String]]("Name of the AWS region to connect to")
    val awsLambdaTimeout = settingKey[Option[Int]]("The Lambda timeout length in seconds (1-300)")
    val awsLambdaMemory = settingKey[Option[Int]]("The amount of memory in MB for the Lambda function (128-1536, multiple of 64)")
    val lambdaHandlers = settingKey[Seq[(String, String)]]("A sequence of pairs of Lambda function names to handlers (for multiple handlers in one jar)")
    val deployMethod = settingKey[Option[String]]("S3 for using an S3 bucket to upload the jar or JAR for directly uploading a JAR file.")
  }

  import autoImport._

  override def requires = sbtassembly.AssemblyPlugin

  override lazy val projectSettings = Seq(
    updateLambda := doUpdateLambda(
      deployMethod = deployMethod.value,
      region = region.value,
      jar = sbtassembly.AssemblyKeys.assembly.value,
      s3Bucket = s3Bucket.value,
      s3KeyPrefix = s3KeyPrefix.?.value,
      lambdaName = lambdaName.value,
      handlerName = handlerName.value,
      lambdaHandlers = lambdaHandlers.value
    ),
    createLambda := doCreateLambda(
      deployMethod = deployMethod.value,
      region = region.value,
      jar = sbtassembly.AssemblyKeys.assembly.value,
      s3Bucket = s3Bucket.value,
      s3KeyPrefix = s3KeyPrefix.?.value,
      lambdaName = lambdaName.value,
      handlerName = handlerName.value,
      lambdaHandlers = lambdaHandlers.value,
      roleArn = roleArn.value,
      timeout = awsLambdaTimeout.value,
      memory = awsLambdaMemory.value
    ),
    s3Bucket := None,
    lambdaName := Some(sbt.Keys.name.value),
    handlerName := None,
    lambdaHandlers := List.empty[(String, String)],
    roleArn := None,
    region := None,
    deployMethod := Some("S3"),
    awsLambdaMemory := None,
    awsLambdaTimeout := None
  )

  private def doUpdateLambda(deployMethod: Option[String], region: Option[String], jar: File, s3Bucket: Option[String], s3KeyPrefix: Option[String],
                             lambdaName: Option[String], handlerName: Option[String], lambdaHandlers: Seq[(String, String)]): Map[String, LambdaARN] = {
    val resolvedDeployMethod = resolveDeployMethod(deployMethod)
    val resolvedRegion = resolveRegion(region)
    val resolvedLambdaHandlers = resolveLambdaHandlers(lambdaName, handlerName, lambdaHandlers)

    if (resolvedDeployMethod.value == "S3") {
      val resolvedBucketId = resolveBucketId(s3Bucket)
      val resolvedS3KeyPrefix = resolveS3KeyPrefix(s3KeyPrefix)

      AwsS3.pushJarToS3(jar, resolvedBucketId, resolvedS3KeyPrefix) match {
        case Success(s3Key) => (for (resolvedLambdaName <- resolvedLambdaHandlers.keys) yield {
          val updateFunctionCodeRequest = createUpdateFunctionCodeRequestFromS3(resolvedBucketId, s3Key, resolvedLambdaName)

          updateFunctionCode(resolvedRegion, resolvedLambdaName, updateFunctionCodeRequest)
        }).toMap
        case Failure(exception) =>
          sys.error(s"Error uploading jar to S3 lambda: ${exception.getLocalizedMessage}\n${exception.getStackTraceString}")
      }
    } else {
      (for (resolvedLambdaName <- resolvedLambdaHandlers.keys) yield {
        val updateFunctionCodeRequest = createUpdateFunctionCodeRequestFromJar(jar, resolvedLambdaName)

        updateFunctionCode(resolvedRegion, resolvedLambdaName, updateFunctionCodeRequest)
      }).toMap
    }
  }

  def updateFunctionCode(resolvedRegion: Region, resolvedLambdaName: LambdaName, updateFunctionCodeRequest: UpdateFunctionCodeRequest): (String, LambdaARN) = {
    AwsLambda.updateLambdaWithFunctionCodeRequest(resolvedRegion, resolvedLambdaName, updateFunctionCodeRequest) match {
      case Success(updateFunctionCodeResult) =>
        resolvedLambdaName.value -> LambdaARN(updateFunctionCodeResult.getFunctionArn)
      case Failure(exception) =>
        sys.error(s"Error updating lambda: ${exception.getLocalizedMessage}\n${exception.getStackTraceString}")
    }
  }

  def createUpdateFunctionCodeRequestFromS3(resolvedBucketId: S3BucketId, s3Key: S3Key, resolvedLambdaName: LambdaName): UpdateFunctionCodeRequest = {
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

  private def doCreateLambda(deployMethod: Option[String], region: Option[String], jar: File, s3Bucket: Option[String], s3KeyPrefix: Option[String], lambdaName: Option[String],
                             handlerName: Option[String], lambdaHandlers: Seq[(String, String)], roleArn: Option[String], timeout: Option[Int], memory: Option[Int]): Map[String, LambdaARN] = {
    val resolvedDeployMethod = resolveDeployMethod(deployMethod)
    val resolvedRegion = resolveRegion(region)
    val resolvedLambdaHandlers = resolveLambdaHandlers(lambdaName, handlerName, lambdaHandlers)
    val resolvedRoleName = resolveRoleARN(roleArn)
    val resolvedTimeout = resolveTimeout(timeout)
    val resolvedMemory = resolveMemory(memory)

    if (resolvedDeployMethod.value == "S3") {
      val resolvedBucketId = resolveBucketId(s3Bucket)
      val resolvedS3KeyPrefix = resolveS3KeyPrefix(s3KeyPrefix)
      AwsS3.pushJarToS3(jar, resolvedBucketId, resolvedS3KeyPrefix) match {
        case Success(s3Key) =>
          for ((resolvedLambdaName, resolvedHandlerName) <- resolvedLambdaHandlers) yield {
            val functionCode = createFunctionCodeFromS3(jar, resolvedBucketId)

            createLambdaWithFunctionCode(jar, resolvedRegion, resolvedRoleName, resolvedTimeout, resolvedMemory,
              resolvedLambdaName, resolvedHandlerName, functionCode)
          }
        case Failure(exception) =>
          sys.error(s"Error upload jar to S3 lambda: ${exception.getLocalizedMessage}\n${exception.getStackTraceString}")
      }
    } else {
      (for ((resolvedLambdaName, resolvedHandlerName) <- resolvedLambdaHandlers) yield {
        val functionCode = createFunctionCodeFromJar(jar)

        createLambdaWithFunctionCode(jar, resolvedRegion, resolvedRoleName, resolvedTimeout, resolvedMemory,
          resolvedLambdaName, resolvedHandlerName, functionCode)
      })
    }
  }

  def createLambdaWithFunctionCode(jar: File, resolvedRegion: Region, resolvedRoleName: RoleARN, resolvedTimeout: Option[Timeout], resolvedMemory: Option[Memory], resolvedLambdaName: LambdaName, resolvedHandlerName: HandlerName, functionCode: FunctionCode): (String, LambdaARN) = {
    AwsLambda.createLambdaWithFunctionCode(resolvedRegion, jar, resolvedLambdaName, resolvedHandlerName, resolvedRoleName,
      resolvedTimeout, resolvedMemory, functionCode) match {
      case Success(createFunctionCodeResult) =>
        resolvedLambdaName.value -> LambdaARN(createFunctionCodeResult.getFunctionArn)
      case Failure(exception) =>
        sys.error(s"Failed to create lambda function: ${exception.getLocalizedMessage}\n${exception.getStackTraceString}")
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

  private def resolveRegion(sbtSettingValueOpt: Option[String]): Region =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.region) map Region getOrElse promptUserForRegion()

  private def resolveDeployMethod(sbtSettingValueOpt: Option[String]): DeployMethod =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.deployMethod) map DeployMethod getOrElse promptUserForDeployMethod()

  private def resolveBucketId(sbtSettingValueOpt: Option[String]): S3BucketId =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.bucketId) map S3BucketId getOrElse promptUserForS3BucketId()

  private def resolveS3KeyPrefix(sbtSettingValueOpt: Option[String]): String =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.s3KeyPrefix) getOrElse ""

  private def resolveLambdaHandlers(lambdaName: Option[String], handlerName: Option[String],
      lambdaHandlers: Seq[(String, String)]): Map[LambdaName, HandlerName] = {
    val lhs = if (lambdaHandlers.nonEmpty) lambdaHandlers.iterator else {
      val l = lambdaName.getOrElse(sys.env.getOrElse(EnvironmentVariables.lambdaName, promptUserForFunctionName()))
      val h = handlerName.getOrElse(sys.env.getOrElse(EnvironmentVariables.handlerName, promptUserForHandlerName()))
      Iterator(l -> h)
    }
    lhs.map { case (l, h) => LambdaName(l) -> HandlerName(h) }.toMap
  }

  private def resolveRoleARN(sbtSettingValueOpt: Option[String]): RoleARN =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.roleArn) map RoleARN getOrElse promptUserForRoleARN()

  private def resolveTimeout(sbtSettingValueOpt: Option[Int]): Option[Timeout] =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.timeout).map(_.toInt) map Timeout

  private def resolveMemory(sbtSettingValueOpt: Option[Int]): Option[Memory] =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.memory).map(_.toInt) map Memory

  private def promptUserForRegion(): Region = {
    val inputValue = readInput(s"Enter the name of the AWS region to connect to. (You also could have set the environment variable: ${EnvironmentVariables.region} or the sbt setting: region)")

    Region(inputValue)
  }

  private def promptUserForDeployMethod(): DeployMethod = {
    val inputValue = readInput(s"Enter the method of deploy you want to use (S3 or JAR). (You also could have set the environment variable: ${EnvironmentVariables.deployMethod} or the sbt setting: deployMethod)")

    DeployMethod(inputValue)
  }

  private def promptUserForS3BucketId(): S3BucketId = {
    val inputValue = readInput(s"Enter the AWS S3 bucket where the lambda jar will be stored. (You also could have set the environment variable: ${EnvironmentVariables.bucketId} or the sbt setting: s3Bucket)")
    val bucketId = S3BucketId(inputValue)

    AwsS3.getBucket(bucketId) map (_ => bucketId) getOrElse {
      val createBucket = readInput(s"Bucket $inputValue does not exist. Create it now? (y/n)")

      if(createBucket == "y") {
        AwsS3.createBucket(bucketId) match {
          case Success(createdBucketId) =>
            createdBucketId
          case Failure(th) =>
            println(s"Failed to create S3 bucket: ${th.getLocalizedMessage}")
            promptUserForS3BucketId()
        }
      }
      else promptUserForS3BucketId()
    }
  }

  private def promptUserForFunctionName(): String =
    readInput(s"Enter the name of the AWS Lambda. (You also could have set the environment variable: ${EnvironmentVariables.lambdaName} or the sbt setting: lambdaName)")

  private def promptUserForHandlerName(): String =
    readInput(s"Enter the name of the AWS Lambda handler. (You also could have set the environment variable: ${EnvironmentVariables.handlerName} or the sbt setting: handlerName)")

  private def promptUserForRoleARN(): RoleARN = {
    AwsIAM.basicLambdaRole() match {
      case Some(basicRole) =>
        val reuseBasicRole = readInput(s"IAM role '${AwsIAM.BasicLambdaRoleName}' already exists. Reuse this role? (y/n)")
        
        if(reuseBasicRole == "y") RoleARN(basicRole.getArn)
        else readRoleARN()
      case None =>
        val createDefaultRole = readInput(s"Default IAM role for AWS Lambda has not been created yet. Create this role now? (y/n)")
        
        if(createDefaultRole == "y") {
          AwsIAM.createBasicLambdaRole() match {
            case Success(createdRole) =>
              createdRole
            case Failure(th) =>
              println(s"Failed to create role: ${th.getLocalizedMessage}")
              promptUserForRoleARN()
          }
        } else readRoleARN()
    }
  }

  private def readRoleARN(): RoleARN = {
    val inputValue = readInput(s"Enter the ARN of the IAM role for the Lambda. (You also could have set the environment variable: ${EnvironmentVariables.roleArn} or the sbt setting: roleArn)")
    RoleARN(inputValue)
  }

  private def readInput(prompt: String): String =
    SimpleReader.readLine(s"$prompt\n") getOrElse {
      val badInputMessage = "Unable to read input"

      val updatedPrompt = if(prompt.startsWith(badInputMessage)) prompt else s"$badInputMessage\n$prompt"

      readInput(updatedPrompt)
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
