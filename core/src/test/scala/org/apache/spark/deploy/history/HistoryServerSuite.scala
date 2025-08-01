/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.deploy.history

import java.io.{File, FileInputStream, FileWriter, InputStream, IOException}
import java.net.{HttpURLConnection, URI, URL}
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import com.google.common.io.{ByteStreams, Files}
import jakarta.servlet._
import jakarta.servlet.http.{HttpServletRequest, HttpServletRequestWrapper, HttpServletResponse}
import org.apache.commons.io.IOUtils
import org.apache.hadoop.fs.{FileStatus, FileSystem, Path}
import org.json4s.JsonAST._
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.JsonMethods._
import org.mockito.Mockito._
import org.openqa.selenium.WebDriver
import org.openqa.selenium.htmlunit.HtmlUnitDriver
import org.scalatest.BeforeAndAfter
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.selenium.WebBrowser

import org.apache.spark._
import org.apache.spark.internal.config._
import org.apache.spark.internal.config.History._
import org.apache.spark.internal.config.Tests.IS_TESTING
import org.apache.spark.internal.config.UI._
import org.apache.spark.status.api.v1.ApplicationInfo
import org.apache.spark.status.api.v1.JobData
import org.apache.spark.tags.{ExtendedLevelDBTest, WebBrowserTest}
import org.apache.spark.ui.SparkUI
import org.apache.spark.util.{ResetSystemProperties, ShutdownHookManager, Utils}
import org.apache.spark.util.ArrayImplicits._

/**
 * Abstract base class for testing the History Server, including mechanisms to compare
 * responses from the JSON metrics API against predefined "golden files". This suite
 * establishes a framework for validating API behaviors across different storage backends.
 *
 * Test cases added here will be executed against all concrete implementations. For backend-specific
 * validations, subclasses should override relevant setup methods or add specialized tests.
 *
 * The test suite supports two operational modes:
 * 1. Validation Mode (default): Compares API responses against existing golden files.
 * 2. Generation Mode: Generates new golden files when SPARK_GENERATE_GOLDEN_FILES=1 is set.
 *
 * To generate golden files, run the following SBT command with the environment variable set:
 * {{{
 *   SPARK_GENERATE_GOLDEN_FILES=1 build/sbt "core/testOnly <sub class>"
 * }}}
 *
 * Note: New golden files should be carefully reviewed to ensure they align with Spark's
 * public API specifications. Changes to metrics should be made with caution, as they
 * are considered part of Spark's public interface.
 */
abstract class HistoryServerSuite extends SparkFunSuite with BeforeAndAfter with Matchers
  with MockitoSugar with JsonTestUtils with Eventually with WebBrowser with LocalSparkContext
  with ResetSystemProperties {

  private val baseResourcePath = getWorkspaceFilePath("core", "src", "test", "resources").toFile
  private val logDir = new File(baseResourcePath, "spark-events").getCanonicalPath
  private val expRoot = new File(baseResourcePath, "HistoryServerExpectations")
  private val storeDir = Utils.createTempDir(namePrefix = "history")

  private var provider: FsHistoryProvider = null
  private var server: HistoryServer = null
  private val localhost: String = Utils.localHostNameForURI()
  private var port: Int = -1

  protected def diskBackend: HybridStoreDiskBackend.Value

  def getExpRoot: File = expRoot

  def init(extraConf: (String, String)*): Unit = {
    Utils.deleteRecursively(storeDir)
    assert(storeDir.mkdir())
    val conf = new SparkConf()
      .set(HISTORY_LOG_DIR, logDir)
      .set(UPDATE_INTERVAL_S.key, "0")
      .set(IS_TESTING, true)
      .set(LOCAL_STORE_DIR, storeDir.getAbsolutePath())
      .set(EVENT_LOG_STAGE_EXECUTOR_METRICS, true)
      .set(EXECUTOR_PROCESS_TREE_METRICS_ENABLED, true)
      .set(HYBRID_STORE_DISK_BACKEND, diskBackend.toString)
    conf.setAll(extraConf)
    provider = new FsHistoryProvider(conf)
    provider.checkForLogs()
    val securityManager = HistoryServer.createSecurityManager(conf)

    server = new HistoryServer(conf, provider, securityManager, 18080)
    server.bind()
    provider.start()
    port = server.boundPort
  }

  def stop(): Unit = {
    server.stop()
    server = null
  }

  before {
    if (server == null) {
      init()
    }
  }

  val cases = Seq(
    "application list json" -> "applications",
    "completed app list json" -> "applications?status=completed",
    "running app list json" -> "applications?status=running",
    "minDate app list json" -> "applications?minDate=2015-02-10",
    "maxDate app list json" -> "applications?maxDate=2015-02-10",
    "maxDate2 app list json" -> "applications?maxDate=2015-02-03T16:42:40.000GMT",
    "minEndDate app list json" -> "applications?minEndDate=2015-05-06T13:03:00.950GMT",
    "maxEndDate app list json" -> "applications?maxEndDate=2015-05-06T13:03:00.950GMT",
    "minEndDate and maxEndDate app list json" ->
      "applications?minEndDate=2015-03-16&maxEndDate=2015-05-06T13:03:00.950GMT",
    "minDate and maxEndDate app list json" ->
      "applications?minDate=2015-03-16&maxEndDate=2015-05-06T13:03:00.950GMT",
    "limit app list json" -> "applications?limit=3",
    "one app json" -> "applications/local-1422981780767",
    "one app multi-attempt json" -> "applications/local-1426533911241",
    "job list json" -> "applications/local-1422981780767/jobs",
    "job list from multi-attempt app json(1)" -> "applications/local-1426533911241/1/jobs",
    "job list from multi-attempt app json(2)" -> "applications/local-1426533911241/2/jobs",
    "one job json" -> "applications/local-1422981780767/jobs/0",
    "succeeded job list json" -> "applications/local-1422981780767/jobs?status=succeeded",
    "succeeded&failed job list json" ->
      "applications/local-1422981780767/jobs?status=succeeded&status=failed",
    "executor list json" -> "applications/local-1422981780767/executors",
    "executor list with executor metrics json" ->
      "applications/application_1553914137147_0018/executors",
    "stage list json" -> "applications/local-1422981780767/stages",
    "complete stage list json" -> "applications/local-1422981780767/stages?status=complete",
    "failed stage list json" -> "applications/local-1422981780767/stages?status=failed",
    "one stage json" -> "applications/local-1422981780767/stages/1",
    "one stage json with details" ->
      "applications/local-1422981780767/stages/1?details=true&taskStatus=success",
    "one stage attempt json" -> "applications/local-1422981780767/stages/1/0",
    "one stage attempt json details with failed task" ->
      "applications/local-1422981780767/stages/1/0?details=true&taskStatus=failed",
    "one stage json with partitionId" -> "applications/local-1642039451826/stages/2",

    "stage task summary w shuffle write"
      -> "applications/local-1430917381534/stages/0/0/taskSummary",
    "stage task summary w shuffle read"
      -> "applications/local-1430917381534/stages/1/0/taskSummary",
    "stage task summary w/ custom quantiles" ->
      "applications/local-1430917381534/stages/0/0/taskSummary?quantiles=0.01,0.5,0.99",

    "stage task list" -> "applications/local-1430917381534/stages/0/0/taskList",
    "stage task list w/ offset & length" ->
      "applications/local-1430917381534/stages/0/0/taskList?offset=10&length=50",
    "stage task list w/ sortBy" ->
      "applications/local-1430917381534/stages/0/0/taskList?sortBy=DECREASING_RUNTIME",
    "stage task list w/ sortBy short names: -runtime" ->
      "applications/local-1430917381534/stages/0/0/taskList?sortBy=-runtime",
    "stage task list w/ sortBy short names: runtime" ->
      "applications/local-1430917381534/stages/0/0/taskList?sortBy=runtime",
    "stage task list w/ status" ->
      "applications/app-20161115172038-0000/stages/0/0/taskList?status=failed",
    "stage task list w/ status & offset & length" ->
      "applications/local-1430917381534/stages/0/0/taskList?status=success&offset=1&length=2",
    "stage task list w/ status & sortBy short names: runtime" ->
      "applications/local-1430917381534/stages/0/0/taskList?status=success&sortBy=runtime",
    "stage task list with partitionId" -> "applications/local-1642039451826/stages/0/0/taskList",

    "stage list with accumulable json" -> "applications/local-1426533911241/1/stages",
    "stage with accumulable json" -> "applications/local-1426533911241/1/stages/0/0",
    "stage task list from multi-attempt app json(1)" ->
      "applications/local-1426533911241/1/stages/0/0/taskList",
    "stage task list from multi-attempt app json(2)" ->
      "applications/local-1426533911241/2/stages/0/0/taskList",
    "excludeOnFailure for stage" -> "applications/app-20180109111548-0000/stages/0/0",
    "excludeOnFailure node for stage" -> "applications/application_1516285256255_0012/stages/0/0",

    "rdd list storage json" -> "applications/local-1422981780767/storage/rdd",
    "executor node excludeOnFailure" -> "applications/app-20161116163331-0000/executors",
    "executor node excludeOnFailure unexcluding" ->
      "applications/app-20161115172038-0000/executors",
    "executor memory usage" -> "applications/app-20161116163331-0000/executors",
    "executor resource information" -> "applications/application_1555004656427_0144/executors",
    "multiple resource profiles" -> "applications/application_1578436911597_0052/environment",
    "stage list with peak metrics" -> "applications/app-20200706201101-0003/stages",
    "stage with peak metrics" -> "applications/app-20200706201101-0003/stages/2/0",
    "stage with summaries" -> "applications/app-20200706201101-0003/stages/2/0?withSummaries=true",

    "app environment" -> "applications/app-20161116163331-0000/environment",

    // Enable "spark.eventLog.logBlockUpdates.enabled", to get the storage information
    // in the history server.
    "one rdd storage json" -> "applications/local-1422981780767/storage/rdd/0",
    "miscellaneous process" ->
      "applications/application_1555004656427_0144/allmiscellaneousprocess",
    "stage with speculation summary" ->
      "applications/application_1628109047826_1317105/stages/0/0/"
  )

  if (regenerateGoldenFiles) {
    Utils.deleteRecursively(expRoot)
    Utils.createDirectory(expRoot)
  }

  // run a bunch of characterization tests -- just verify the behavior is the same as what is saved
  // in the test resource folder
  cases.foreach { case (name, path) =>
    test(name) {
      val (code, jsonOpt, errOpt) = getContentAndCode(path)
      code should be (HttpServletResponse.SC_OK)
      jsonOpt should be (Symbol("defined"))
      errOpt should be (None)

      val goldenFile =
        new File(expRoot, HistoryServerSuite.sanitizePath(name) + "_expectation.json")
      val jsonAst = parse(clearLastUpdated(jsonOpt.get))

      if (regenerateGoldenFiles) {
        Utils.tryWithResource(new FileWriter(goldenFile)) { out =>
          val sortedJson = jsonAst.transform {
            case JObject(fields) => JObject(fields.sortBy(_._1))
          }
          out.write(pretty(render(sortedJson)))
          out.write('\n')
        }
      }

      val exp = IOUtils.toString(new FileInputStream(goldenFile), StandardCharsets.UTF_8)
      // compare the ASTs so formatting differences don't cause failures
      val expAst = parse(exp)
      assertValidDataInJson(jsonAst, expAst)
    }
  }

  // SPARK-10873 added the lastUpdated field for each application's attempt,
  // the REST API returns the last modified time of EVENT LOG file for this field.
  // It is not applicable to hard-code this dynamic field in a static expected file,
  // so here we skip checking the lastUpdated field's value (setting it as "").
  private def clearLastUpdated(json: String): String = {
    if (json.indexOf("lastUpdated") >= 0) {
      val subStrings = json.split(",")
      for (i <- subStrings.indices) {
        if (subStrings(i).indexOf("lastUpdatedEpoch") >= 0) {
          subStrings(i) = subStrings(i).replaceAll("(\\d+)", "0")
        } else if (subStrings(i).indexOf("lastUpdated") >= 0) {
          val regex = "\"lastUpdated\"\\s*:\\s*\".*\"".r
          subStrings(i) = regex.replaceAllIn(subStrings(i), "\"lastUpdated\" : \"\"")
        }
      }
      subStrings.mkString(",")
    } else {
      json
    }
  }

  test("download all logs for app with multiple attempts") {
    doDownloadTest("local-1430917381535", None)
  }

  test("download one log for app with multiple attempts") {
    (1 to 2).foreach { attemptId => doDownloadTest("local-1430917381535", Some(attemptId)) }
  }

  // Test that the files are downloaded correctly, and validate them.
  def doDownloadTest(appId: String, attemptId: Option[Int]): Unit = {

    val url = attemptId match {
      case Some(id) =>
        new URI(s"${generateURL(s"applications/$appId")}/$id/logs").toURL
      case None =>
        new URI(s"${generateURL(s"applications/$appId")}/logs").toURL
    }

    val (code, inputStream, error) = HistoryServerSuite.connectAndGetInputStream(url)
    code should be (HttpServletResponse.SC_OK)
    inputStream should not be None
    error should be (None)

    val zipStream = new ZipInputStream(inputStream.get)
    var entry = zipStream.getNextEntry
    entry should not be null
    val totalFiles = {
      attemptId.map { x => 1 }.getOrElse(2)
    }
    var filesCompared = 0
    while (entry != null) {
      if (!entry.isDirectory) {
        val expectedFile = {
          new File(logDir, entry.getName)
        }
        val expected = Files.asCharSource(expectedFile, StandardCharsets.UTF_8).read()
        val actual = new String(ByteStreams.toByteArray(zipStream), StandardCharsets.UTF_8)
        actual should be (expected)
        filesCompared += 1
      }
      entry = zipStream.getNextEntry
    }
    filesCompared should be (totalFiles)
  }

  test("response codes on bad paths") {
    val badAppId = getContentAndCode("applications/foobar")
    badAppId._1 should be (HttpServletResponse.SC_NOT_FOUND)
    badAppId._3 should be (Some("unknown app: foobar"))

    val badStageId = getContentAndCode("applications/local-1422981780767/stages/12345")
    badStageId._1 should be (HttpServletResponse.SC_NOT_FOUND)
    badStageId._3 should be (Some("unknown stage: 12345"))

    val badStageAttemptId = getContentAndCode("applications/local-1422981780767/stages/1/1")
    badStageAttemptId._1 should be (HttpServletResponse.SC_NOT_FOUND)
    badStageAttemptId._3 should be (Some("unknown attempt for stage 1.  Found attempts: [0]"))

    val badStageId2 = getContentAndCode("applications/local-1422981780767/stages/flimflam")
    badStageId2._1 should be (HttpServletResponse.SC_NOT_FOUND)
    // will take some mucking w/ jersey to get a better error msg in this case

    val badQuantiles = getContentAndCode(
      "applications/local-1430917381534/stages/0/0/taskSummary?quantiles=foo,0.1")
    badQuantiles._1 should be (HttpServletResponse.SC_BAD_REQUEST)
    badQuantiles._3 should be (Some("Bad value for parameter \"quantiles\".  Expected a double, " +
      "got \"foo\""))

    getContentAndCode("foobar")._1 should be (HttpServletResponse.SC_NOT_FOUND)
  }

  test("automatically retrieve uiRoot from request through Knox") {
    assert(sys.props.get("spark.ui.proxyBase").isEmpty,
      "spark.ui.proxyBase is defined but it should not for this UT")
    assert(sys.env.get("APPLICATION_WEB_PROXY_BASE").isEmpty,
      "APPLICATION_WEB_PROXY_BASE is defined but it should not for this UT")
    val page = new HistoryPage(server)
    val requestThroughKnox = mock[HttpServletRequest]
    val knoxBaseUrl = "/gateway/default/sparkhistoryui"
    when(requestThroughKnox.getHeader("X-Forwarded-Context")).thenReturn(knoxBaseUrl)
    val responseThroughKnox = page.render(requestThroughKnox)

    val urlsThroughKnox = responseThroughKnox \\ "@href" map (_.toString)
    val siteRelativeLinksThroughKnox = urlsThroughKnox filter (_.startsWith("/"))
    for (link <- siteRelativeLinksThroughKnox) {
      link should startWith (knoxBaseUrl)
    }

    val directRequest = mock[HttpServletRequest]
    val directResponse = page.render(directRequest)

    val directUrls = directResponse \\ "@href" map (_.toString)
    val directSiteRelativeLinks = directUrls filter (_.startsWith("/"))
    for (link <- directSiteRelativeLinks) {
      link should not startWith (knoxBaseUrl)
    }
  }

  test("static relative links are prefixed with uiRoot (spark.ui.proxyBase)") {
    val uiRoot = Option(System.getenv("APPLICATION_WEB_PROXY_BASE")).getOrElse("/testwebproxybase")
    val page = new HistoryPage(server)
    val request = mock[HttpServletRequest]

    // when
    System.setProperty("spark.ui.proxyBase", uiRoot)
    val response = page.render(request)

    // then
    val urls = response \\ "@href" map (_.toString)
    val siteRelativeLinks = urls filter (_.startsWith("/"))
    for (link <- siteRelativeLinks) {
      link should startWith (uiRoot)
    }
  }

  test("/version api endpoint") {
    val response = getUrl("version")
    assert(response.contains(SPARK_VERSION))
  }

  /**
   * Verify that the security manager needed for the history server can be instantiated
   * when `spark.authenticate` is `true`, rather than raise an `IllegalArgumentException`.
   */
  test("security manager starts with spark.authenticate set") {
    val conf = new SparkConf()
      .set(IS_TESTING, true)
      .set(SecurityManager.SPARK_AUTH_CONF, "true")
    HistoryServer.createSecurityManager(conf)
  }

  test("incomplete apps get refreshed") {
    implicit val webDriver: WebDriver = new HtmlUnitDriver
    implicit val formats = org.json4s.DefaultFormats

    // this test dir is explicitly deleted on successful runs; retained for diagnostics when
    // not
    val logDir = Utils.createDirectory(System.getProperty("java.io.tmpdir", "logs"))

    // a new conf is used with the background thread set and running at its fastest
    // allowed refresh rate (1Hz)
    stop()
    // Like 'init()', we need to clear the store directory of previously stopped server.
    Utils.deleteRecursively(storeDir)
    assert(storeDir.mkdir())
    val myConf = new SparkConf()
      .set(HISTORY_LOG_DIR, logDir.getAbsolutePath)
      .set(EVENT_LOG_DIR, logDir.getAbsolutePath)
      .set(UPDATE_INTERVAL_S.key, "1s")
      .set(EVENT_LOG_ENABLED, true)
      .set(LOCAL_STORE_DIR, storeDir.getAbsolutePath())
      .remove(IS_TESTING)
      .set(HYBRID_STORE_DISK_BACKEND, diskBackend.toString)
    val provider = new FsHistoryProvider(myConf)
    val securityManager = HistoryServer.createSecurityManager(myConf)

    sc = new SparkContext("local", "test", myConf)
    val logDirUri = logDir.toURI
    val logDirPath = new Path(logDirUri)
    val fs = FileSystem.get(logDirUri, sc.hadoopConfiguration)

    def listDir(dir: Path): Seq[FileStatus] = {
      val statuses = fs.listStatus(dir)
      statuses.flatMap(
        stat => if (stat.isDirectory) listDir(stat.getPath) else Seq(stat)).toImmutableArraySeq
    }

    def dumpLogDir(msg: String = ""): Unit = {
      if (log.isDebugEnabled) {
        logDebug(msg)
        listDir(logDirPath).foreach { status =>
          val s = status.toString
          logDebug(s)
        }
      }
    }

    server = new HistoryServer(myConf, provider, securityManager, 0)
    server.bind()
    provider.start()
    val port = server.boundPort
    val metrics = server.cacheMetrics

    // build a URL for an app or app/attempt plus a page underneath
    def buildURL(appId: String, suffix: String): URL = {
      new URI(s"http://$localhost:$port/history/$appId$suffix").toURL
    }

    // build a rest URL for the application and suffix.
    def applications(appId: String, suffix: String): URL = {
      new URI(s"http://$localhost:$port/api/v1/applications/$appId$suffix").toURL
    }

    // start initial job
    val d = sc.parallelize(1 to 10)
    d.count()
    val stdInterval = interval(100.milliseconds)
    val appId = eventually(timeout(20.seconds), stdInterval) {
      val json = getContentAndCode("applications", port)._2.get
      val apps = parse(json).asInstanceOf[JArray].arr
      apps should have size 1
      (apps.head \ "id").extract[String]
    }

    val appIdRoot = buildURL(appId, "")
    val rootAppPage = HistoryServerSuite.getUrl(appIdRoot)
    logDebug(s"$appIdRoot ->[${rootAppPage.length}] \n$rootAppPage")
    // sanity check to make sure filter is chaining calls
    rootAppPage should not be empty

    def getAppUI: SparkUI = {
      server.withSparkUI(appId, None) { ui => ui }
    }

    // selenium isn't that useful on failures...add our own reporting
    def getNumJobs(suffix: String): Int = {
      val target = buildURL(appId, suffix)
      val targetBody = HistoryServerSuite.getUrl(target)
      try {
        go to target.toExternalForm
        findAll(cssSelector("tbody tr")).toIndexedSeq.size
      } catch {
        case ex: Exception =>
          throw new Exception(s"Against $target\n$targetBody", ex)
      }
    }
    // use REST API to get #of jobs
    def getNumJobsRestful(): Int = {
      val json = HistoryServerSuite.getUrl(applications(appId, "/jobs"))
      val jsonAst = parse(json)
      val jobList = jsonAst.asInstanceOf[JArray]
      jobList.values.size
    }

    // get a list of app Ids of all apps in a given state. REST API
    def listApplications(completed: Boolean): Seq[String] = {
      val json = parse(HistoryServerSuite.getUrl(applications("", "")))
      logDebug(s"${JsonMethods.pretty(json)}")
      json match {
        case JNothing => Seq()
        case apps: JArray =>
          apps.children.filter(app => {
            (app \ "attempts") match {
              case attempts: JArray =>
                val state = (attempts.children.head \ "completed").asInstanceOf[JBool]
                state.value == completed
              case _ => false
            }
          }).map(app => (app \ "id").asInstanceOf[JString].values)
        case _ => Seq()
      }
    }

    def completedJobs(): Seq[JobData] = {
      getAppUI.store.jobsList(List(JobExecutionStatus.SUCCEEDED).asJava)
    }

    def activeJobs(): Seq[JobData] = {
      getAppUI.store.jobsList(List(JobExecutionStatus.RUNNING).asJava)
    }

    def isApplicationCompleted(appInfo: ApplicationInfo): Boolean = {
      appInfo.attempts.nonEmpty && appInfo.attempts.head.completed
    }

    activeJobs() should have size 0
    completedJobs() should have size 1
    getNumJobs("") should be (1)
    getNumJobs("/jobs") should be (1)
    getNumJobsRestful() should be (1)
    assert(metrics.lookupCount.getCount > 0, s"lookup count too low in $metrics")

    // dump state before the next bit of test, which is where update
    // checking really gets stressed
    dumpLogDir("filesystem before executing second job")
    logDebug(s"History Server: $server")

    val d2 = sc.parallelize(1 to 10)
    d2.count()
    dumpLogDir("After second job")

    val stdTimeout = timeout(10.seconds)
    logDebug("waiting for UI to update")
    eventually(stdTimeout, stdInterval) {
      assert(2 === getNumJobs(""),
        s"jobs not updated, server=$server\n dir = ${listDir(logDirPath)}")
      assert(2 === getNumJobs("/jobs"),
        s"job count under /jobs not updated, server=$server\n dir = ${listDir(logDirPath)}")
      getNumJobsRestful() should be(2)
    }

    d.count()
    d.count()
    eventually(stdTimeout, stdInterval) {
      assert(4 === getNumJobsRestful(), s"two jobs back-to-back not updated, server=$server\n")
    }
    assert(!isApplicationCompleted(provider.getListing().next()))

    listApplications(false) should contain(appId)

    // stop the spark context
    resetSparkContext()
    // check the app is now found as completed
    eventually(stdTimeout, stdInterval) {
      assert(isApplicationCompleted(provider.getListing().next()),
        s"application never completed, server=$server\n")
    }

    // app becomes observably complete
    eventually(stdTimeout, stdInterval) {
      listApplications(true) should contain (appId)
    }
    // app is no longer incomplete
    listApplications(false) should not contain(appId)

    eventually(stdTimeout, stdInterval) {
      assert(4 === getNumJobsRestful())
    }

    // no need to retain the test dir now the tests complete
    ShutdownHookManager.registerShutdownDeleteDir(logDir)
  }

  test("ui and api authorization checks") {
    val appId = "local-1430917381535"
    val owner = "irashid"
    val admin = "root"
    val other = "alice"

    stop()
    init(
      UI_FILTERS.key -> classOf[FakeAuthFilter].getName(),
      HISTORY_SERVER_UI_ACLS_ENABLE.key -> "true",
      HISTORY_SERVER_UI_ADMIN_ACLS.key -> admin)

    val tests = Seq(
      (owner, HttpServletResponse.SC_OK),
      (admin, HttpServletResponse.SC_OK),
      (other, HttpServletResponse.SC_FORBIDDEN),
      // When the remote user is null, the code behaves as if auth were disabled.
      (null, HttpServletResponse.SC_OK))

    val port = server.boundPort
    val testUrls = Seq(
      s"http://$localhost:$port/api/v1/applications/$appId/1/jobs",
      s"http://$localhost:$port/history/$appId/1/jobs/",
      s"http://$localhost:$port/api/v1/applications/$appId/logs",
      s"http://$localhost:$port/api/v1/applications/$appId/1/logs",
      s"http://$localhost:$port/api/v1/applications/$appId/2/logs")

    tests.foreach { case (user, expectedCode) =>
      testUrls.foreach { url =>
        val headers = if (user != null) Seq(FakeAuthFilter.FAKE_HTTP_USER -> user) else Nil
        val sc = TestUtils.httpResponseCode(new URI(url).toURL, headers = headers)
        assert(sc === expectedCode, s"Unexpected status code $sc for $url (user = $user)")
      }
    }
  }

  test("SPARK-33215: speed up event log download by skipping UI rebuild") {
    val appId = "local-1430917381535"

    stop()
    init()

    val port = server.boundPort
    val testUrls = Seq(
      s"http://$localhost:$port/api/v1/applications/$appId/logs",
      s"http://$localhost:$port/api/v1/applications/$appId/1/logs",
      s"http://$localhost:$port/api/v1/applications/$appId/2/logs")

    testUrls.foreach { url =>
      TestUtils.httpResponseCode(new URI(url).toURL)
    }
    assert(server.cacheMetrics.loadCount.getCount === 0, "downloading event log shouldn't load ui")
  }

  test("access history application defaults to the last attempt id") {
    val oneAttemptAppId = "local-1430917381534"
    HistoryServerSuite.getUrl(buildPageAttemptUrl(oneAttemptAppId, None))

    val multiAttemptAppid = "local-1430917381535"
    val lastAttemptId = Some(2)
    val lastAttemptUrl = buildPageAttemptUrl(multiAttemptAppid, lastAttemptId)
    Seq(None, Some(1), Some(2)).foreach { attemptId =>
      val url = buildPageAttemptUrl(multiAttemptAppid, attemptId)
      val (code, location) = getRedirectUrl(url)
      assert(code === 302, s"Unexpected status code $code for $url")
      attemptId match {
        case None =>
          assert(location.stripSuffix("/") === lastAttemptUrl.toString)
        case _ =>
          assert(location.stripSuffix("/") === url.toString)
      }
      HistoryServerSuite.getUrl(new URI(location).toURL)
    }
  }

  test("Redirect URLs should end with a slash") {
    val oneAttemptAppId = "local-1430917381534"
    val multiAttemptAppid = "local-1430917381535"

    val url = buildPageAttemptUrl(oneAttemptAppId, None)
    val (code, location) = getRedirectUrl(url)
    assert(code === 302, s"Unexpected status code $code for $url")
    assert(location === url.toString + "/")

    val url2 = buildPageAttemptUrl(multiAttemptAppid, None)
    val (code2, location2) = getRedirectUrl(url2)
    assert(code2 === 302, s"Unexpected status code $code2 for $url2")
    assert(location2 === url2.toString + "/2/")
  }

  def getRedirectUrl(url: URL): (Int, String) = {
    val connection = url.openConnection().asInstanceOf[HttpURLConnection]
    connection.setRequestMethod("GET")
    connection.setUseCaches(false)
    connection.setDefaultUseCaches(false)
    connection.setInstanceFollowRedirects(false)
    connection.connect()
    val code = connection.getResponseCode()
    val location = connection.getHeaderField("Location")
    (code, location)
  }

  def buildPageAttemptUrl(appId: String, attemptId: Option[Int]): URL = {
    attemptId match {
      case Some(id) =>
        new URI(s"http://$localhost:$port/history/$appId/$id").toURL
      case None =>
        new URI(s"http://$localhost:$port/history/$appId").toURL
    }
  }

  def getContentAndCode(path: String, port: Int = port): (Int, Option[String], Option[String]) = {
    HistoryServerSuite.getContentAndCode(new URI(s"http://$localhost:$port/api/v1/$path").toURL)
  }

  def getUrl(path: String): String = {
    HistoryServerSuite.getUrl(generateURL(path))
  }

  def generateURL(path: String): URL = {
    new URI(s"http://$localhost:$port/api/v1/$path").toURL
  }

  test("SPARK-31697: HistoryServer should set Content-Type") {
    val port = server.boundPort
    val nonExistenceAppId = "local-non-existence"
    val url = new URI(s"http://$localhost:$port/history/$nonExistenceAppId").toURL
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("GET")
    conn.connect()
    val expectedContentType = "text/html;charset=utf-8"
    val actualContentType = conn.getContentType
    assert(actualContentType === expectedContentType)
  }

  test("Redirect to the root page when accessed to /history/") {
    val port = server.boundPort
    val url = new URI(s"http://$localhost:$port/history/").toURL
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("GET")
    conn.setUseCaches(false)
    conn.setDefaultUseCaches(false)
    conn.setInstanceFollowRedirects(false)
    conn.connect()
    assert(conn.getResponseCode === 302)
    assert(conn.getHeaderField("Location") === s"http://$localhost:$port/")
  }
}

object HistoryServerSuite {

  def getContentAndCode(url: URL): (Int, Option[String], Option[String]) = {
    val (code, in, errString) = connectAndGetInputStream(url)
    val inString = in.map(IOUtils.toString(_, StandardCharsets.UTF_8))
    (code, inString, errString)
  }

  def connectAndGetInputStream(url: URL): (Int, Option[InputStream], Option[String]) = {
    val connection = url.openConnection().asInstanceOf[HttpURLConnection]
    connection.setRequestMethod("GET")
    connection.connect()
    val code = connection.getResponseCode()
    val inStream = try {
      Option(connection.getInputStream())
    } catch {
      case io: IOException => None
    }
    val errString = try {
      val err = Option(connection.getErrorStream())
      err.map(IOUtils.toString(_, StandardCharsets.UTF_8))
    } catch {
      case io: IOException => None
    }
    (code, inStream, errString)
  }


  def sanitizePath(path: String): String = {
    // this doesn't need to be perfect, just good enough to avoid collisions
    path.replaceAll("\\W", "_")
  }

  def getUrl(path: URL): String = {
    val (code, resultOpt, error) = getContentAndCode(path)
    if (code == 200) {
      resultOpt.get
    } else {
      throw new RuntimeException(
        "got code: " + code + " when getting " + path + " w/ error: " + error)
    }
  }
}

/**
 * A filter used for auth tests; sets the request's user to the value of the "HTTP_USER" header.
 */
class FakeAuthFilter extends Filter {
  override def doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain): Unit = {
    val hreq = req.asInstanceOf[HttpServletRequest]
    val wrapped = new HttpServletRequestWrapper(hreq) {
      override def getRemoteUser(): String = hreq.getHeader(FakeAuthFilter.FAKE_HTTP_USER)
    }
    chain.doFilter(wrapped, res)
  }

}

object FakeAuthFilter {
  val FAKE_HTTP_USER = "HTTP_USER"
}

// scalastyle:off line.size.limit
/**
 * Test suite for the History Server using LevelDB as the event log storage backend.
 * Extends HistoryServerSuite and focuses on validating LevelDB-specific behaviors.
 *
 * To generate golden files for this backend:
 * {{{
 *   SPARK_GENERATE_GOLDEN_FILES=1 build/sbt "core/testOnly org.apache.spark.deploy.history.LevelDBBackendHistoryServerSuite"
 * }}}
 */
// scalastyle:on line.size.limit
@WebBrowserTest
@ExtendedLevelDBTest
class LevelDBBackendHistoryServerSuite extends HistoryServerSuite {
  override protected def diskBackend: History.HybridStoreDiskBackend.Value =
    HybridStoreDiskBackend.LEVELDB
}

// scalastyle:off line.size.limit
/**
 * Test suite for the History Server using RocksDB as the event log storage backend.
 * Extends HistoryServerSuite and focuses on validating RocksDB-specific behaviors.
 *
 * To generate golden files for this backend:
 * {{{
 *   SPARK_GENERATE_GOLDEN_FILES=1 build/sbt "core/testOnly org.apache.spark.deploy.history.RocksDBBackendHistoryServerSuite"
 * }}}
 */
// scalastyle:on line.size.limit
@WebBrowserTest
class RocksDBBackendHistoryServerSuite extends HistoryServerSuite {
  override protected def diskBackend: History.HybridStoreDiskBackend.Value =
    HybridStoreDiskBackend.ROCKSDB
}
