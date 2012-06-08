package com.thed.service

import impl.zie.AbstractImportManager
import java.lang.Long
import java.io.File
import io.Source
import com.thed.zfj.rest.JiraService
import com.thed.zfj.model.TestStep
import xml.pull._
import com.thed.util.{ObjectUtil, Constants}
import scala.collection.JavaConversions._
import com.thed.model._
import java.util.Date
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


/**
 * Created with IntelliJ IDEA.
 * User: smangal
 * Date: 5/11/12
 * Time: 12:17 PM
 * To change this template use File | Settings | File Templates.
 */

class TestLinkImporterManagerImpl extends AbstractImportManager{

  private val log:Log = LogFactory.getLog(classOf[TestLinkImporterManagerImpl]);


  var currentTestCase:Testcase = null;
  var currentTestStep:TestStepDetailBase = null
  var steps = List[TestStepDetailBase]()
  var keywords = List[String]()


  override def importAllFiles(importJob: ImportJob, action: String, userId: Long) = {
    importTestLinkFile(importJob, userId);
  }

  def importTestLinkFile(job: ImportJob, long: Long): Boolean = {
    importSingleFiles(null, job, null, null)
  }

  def importSingleFiles(file: File, importJob: ImportJob, action: String, userId: Long) = {
    val testLinkFile = new File(importJob.getFolder);
    if (!testLinkFile.exists()) {
      addJobHistory(importJob, "File: " + importJob.getName + " not found.");
    }
    importJob.setStatus(Constants.IMPORT_JOB_IMPORT_IN_PROGRESS);
    process(importJob, userId);
  }

  def process(job: ImportJob, userId: Long): Boolean = {
    val xmlr = new XMLEventReader(Source.fromFile(job.getFolder))
    while(xmlr.hasNext){
      processEvents(xmlr, job, userId);
    }
    xmlr.stop()
    true
  }

  def processEvents(xmlr: XMLEventReader, job: ImportJob, userId: Long) = {
    var eventType = xmlr.next
    eventType match {
      case EvElemStart(_, "testsuite", _, _) => {

      }
      case EvElemStart(_, "testcases", _, _) => {

      }
      case EvElemStart(_, "testcase", _, _) => {
        keywords = List[String]()
        currentTestCase = new Testcase()
        currentTestCase.setName(getAttributeValue(eventType, "name"))
      }
      case EvElemStart(_, "summary", _, _) => {
        currentTestCase.setComments(ObjectUtil.htmlToText(getText(xmlr, "summary")))
      }

      case EvElemStart(_, "custom_field", _, _) =>
        setCustomFieldValue(xmlr, job.getFieldMap)

      case EvElemStart(_, "keywords", _, _) => {
        keywords = List[String]()
      }
      case EvElemStart(_, "keyword", _, _) => {
        keywords ++= List(getText(xmlr, "keyword"))
      }
      case EvElemStart(_, "notes", _, _) => {

      }




      //Deal with steps
      case EvElemStart(_, "steps", _, _) => {
        steps = List[TestStepDetailBase]()
      }
      case EvElemStart(_, "step", _, _) => {
        currentTestStep = new TestStepDetailBase()
        steps ++= List(currentTestStep) //Append new element as list
      }

      case EvElemStart(_, "actions", _, _) => {
        currentTestStep.setStep(ObjectUtil.htmlToText(getText(xmlr, "actions")))
      }

      case EvElemStart(_, "expectedresults", _, _) => {
        currentTestStep.setResult(ObjectUtil.htmlToText(getText(xmlr, "expectedresults")))
      }



      //END
      case EvElemEnd(_, "testsuite") => {

      }
      case EvElemEnd(_, "testcases") => {

      }
      case EvElemEnd(_, "testcase") => {
        if(keywords.size > 0)
          currentTestCase.setTag(keywords.reduceLeft(_ + ", " + _));
        try {
          val issueId: String = JiraService.saveTestcase(currentTestCase);
          job.getHistory.add(new JobHistory(new Date(), "Issue " + issueId + " created!"))
          steps.foreach(ts => JiraService.saveTestStep(issueId, new TestStep(ts.getStep(), ts.getData(), ts.getResult())))
          job.getHistory.add(new JobHistory(new Date(), "Steps for Issue " + issueId + " created!"))
        }
        catch {
          case e:Exception => log.fatal("Error in creating testcase, skipping to next", e)
        }
      }
      case EvElemEnd(_, "steps") => {

      }
      case _ =>

    }
  }

  private def getAttributeValue(event: XMLEvent, label: String): String = {
    event.asInstanceOf[EvElemStart].attrs.get(label) match {
      case Some(res) => res.head.toString
      case None => "missing " + label
    }
  }

  private def getText( parser : XMLEventReader, inTag : String ) : String = {
    var fullText = new StringBuffer()
    var done = false
    while ( parser.hasNext && !done ){
      parser.next match{
        case EvElemEnd(_, tagName ) =>{
          assert( tagName.equalsIgnoreCase(inTag) )
          done = true
        }
        case EvText( text ) =>{
          fullText.append( text )
        }
        case _ =>
      }
    }
    return fullText.toString()
  }

  def setCustomFieldValue(xmlr: XMLEventReader, map:FieldMap){
    var name:String = null
    var value:String = null
    var done = false
    while(xmlr.hasNext && !done){
      xmlr.next match{
        case EvElemStart(_, "name", _, _) =>{
          name = getText(xmlr, "name")
        }
        case EvElemStart(_, "value", _, _) =>{
          value = getText(xmlr, "value")
          done = true;
        }
        case _ =>
      }
    }
    if (currentTestCase.getCustomProperties.containsKey(name)){
      log.warn(">>>>> this will override previous value, pl make sure to append it. ")
    }
    var customFieldName:String = null
    var fldConfig:FieldConfig = null
    //todo - performance warning, do it once
    map.getFieldMapDetails.foreach(fmapDetail => if (fmapDetail.getMappedField == name) {customFieldName =  fmapDetail.getZephyrField; fldConfig = Constants.fieldConfigs.get(fmapDetail.getZephyrField)})
    if (customFieldName == null){
      log.error("No mapping found for customField " + name + ", skipping custom field")
      return
    }
    populateCustomField(currentTestCase, fldConfig, value)
  }

  def cleanUp(importJob: ImportJob):Boolean = {
    true
  }
}