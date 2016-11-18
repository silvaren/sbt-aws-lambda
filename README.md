# sbt-aws-lambda

sbt plugin to deploy code to AWS Lambda

[![Join the chat at https://gitter.im/gilt/sbt-aws-lambda](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/gilt/sbt-aws-lambda?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)  [![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.gilt.sbt/sbt-aws-lambda/badge.svg?style=plastic)](https://maven-badges.herokuapp.com/maven-central/com.gilt.sbt/sbt-aws-lambda)


Installation
------------

Add the following to your `project/plugins.sbt` file:

```scala
addSbtPlugin("com.gilt.sbt" % "sbt-aws-lambda" % "0.4.2")
```

Add the `AwsLambdaPlugin` auto-plugin to your build.sbt:

```scala
enablePlugins(AwsLambdaPlugin)
```



Usage
-------------
`sbt createLambda` creates a new AWS Lambda function from the current project.

`sbt updateLambda` updates an existing AWS Lambda function with the current project.


Configuration
-------------

sbt-aws-lambda can be configured using sbt settings, environment variables or by reading user input at deploy time

| sbt setting   |      Environment variable      |  Description |
|:----------|:-------------:|:------|
| s3Bucket |  AWS_LAMBDA_BUCKET_ID | The name of an S3 bucket where the lambda code will be stored |
| s3KeyPrefix | AWS_LAMBDA_S3_KEY_PREFIX | The prefix to the S3 key where the jar will be uploaded |
| lambdaName |    AWS_LAMBDA_NAME   |   The name to use for this AWS Lambda function. Defaults to the project name |
| handlerName | AWS_LAMBDA_HANDLER_NAME |    Java class name and method to be executed, e.g. `com.gilt.example.Lambda::myMethod` |
| roleArn | AWS_LAMBDA_IAM_ROLE_ARN |The [ARN](http://docs.aws.amazon.com/general/latest/gr/aws-arns-and-namespaces.html "AWS ARN documentation") of an [IAM](https://aws.amazon.com/iam/ "AWS IAM documentation") role to use when creating a new Lambda |
| region |  AWS_REGION | The name of the AWS region to connect to. Defaults to `us-east-1` |
| awsLambdaTimeout |            | The Lambda timeout in seconds (1-300). Defaults to AWS default. |
| awsLambdaMemory |             | The amount of memory in MB for the Lambda function (128-1536, multiple of 64). Defaults to AWS default. |
| lambdaHandlers |              | Sequence of Lambda names to handler functions (for multiple lambda methods per project). Overrides `lambdaName` and `handlerName` if present. | 
| deployMethod |                | The preferred method for uploading the jar, either `S3` for uploading to AWS S3 or `JAR` for direct upload to AWS Lambda |

An example configuration might look like this:


```scala
retrieveManaged := true

enablePlugins(AwsLambdaPlugin)

lambdaHandlers := Seq(
  "function1"                 -> "com.gilt.example.Lambda::handleRequest1",
  "function2"                 -> "com.gilt.example.Lambda::handleRequest2",
  "function3"                 -> "com.gilt.example.OtherLambda::handleRequest3"
)

// or, instead of the above, for just one function/handler
//
// lambdaName := Some("function1")
//
// handlerName := Some("com.gilt.example.Lambda::handleRequest1")

s3Bucket := Some("lambda-jars")

awsLambdaMemory := Some(192)

awsLambdaTimeout := Some(30)

roleArn := Some("arn:aws:iam::123456789000:role/lambda_basic_execution")

```
(note that you will need to use a real ARN for your role rather than copying this one).


Publishing new versions of this plugin
--------------------------------------

This plugin uses [sbt-sonatype](https://github.com/xerial/sbt-sonatype) to publish to Gilt's account on maven central

```
sbt publishSigned sonatypeRelease
```
