package controllers

import play.api.mvc._
import play.api.Logger
import controllers.authentication.Authentication
import models.Course
import service.{AdditionalDocumentAdder, FileUploader, ResourceHelper}
import java.io.ByteArrayInputStream
import dataAccess.ResourceController
import scala.concurrent.{ExecutionContext, Future}
import ExecutionContext.Implicits.global
import play.api.libs.json.{JsObject, Json}

/**
 * Controller associated with CaptionAider.
 */
object CaptionAider extends Controller {

  /**
   * View CaptionAider. You specify the ID of the content and the ID of the course under whose context we will operate.
   * If there is no course, specify 0 as the ID.
   */
  def view(id: Long, courseId: Long) = Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        ContentController.getContent(id) { content =>
          val course = Course.findById(courseId)
          Future {
            Ok(views.html.captionAider.view(content, course, ResourceController.baseUrl))
          }
        }
  }

  /**
   * Saves a track. To be used as an AJAX call.
   * Expected parameters:
   * - contentId: The content ID
   * - file: an uploaded file
   * - label: The title of the track (no extension)
   * - language: The language of the track
   * - kind: "subtitles" or "captions"
   */
  def save = Authentication.authenticatedAction(parse.multipartFormData) {
    implicit request =>
      implicit user =>

        val params = request.body.dataParts.mapValues(_(0))
        val contentId = params("contentId").toLong
        ContentController.getContent(contentId) { content =>
          request.body.file("file").map[Future[Result]] { tmpFile =>
            val label = params("label")
            val languages = List(params("language"))
            val kind = params("kind")

            // We need to determine if this file has already been saved
            val resourceId = params.getOrElse("resourceId","")

            val mime = tmpFile.contentType.getOrElse("text/plain")
            val file = tmpFile.ref.file
            val size = file.length()

            if (resourceId.isEmpty) {

              // Create a new resource
              // Upload the data
              val name = FileUploader.uniqueFilename(tmpFile.filename)
              FileUploader.uploadFile(file, name, mime).flatMap { url =>
                // Create subtitle (subject) resource
                val resource = ResourceHelper.make.resource(Json.obj(
                  "title" -> label,
                  "type" -> "document",
                  "languages" -> Json.obj(
                    "iso639_3" -> languages
                  )
                ))
                ResourceHelper.createResourceWithUri(resource, user, url, size, mime)
                  .flatMap { json =>
                    val subjectId = (json \ "id").as[String]
                    AdditionalDocumentAdder.add(content, subjectId, 'captionTrack, Json.obj("kind" -> kind)) { _ => Ok(json) }
                  }.recover { case e =>
                    Logger.debug("Could not create resource: " + e.getMessage())
                    InternalServerError("Could not create resource")
                  }
              }.recover { case e =>
                Logger.debug("Could not upload file: " + e.getMessage())
                InternalServerError("Could not upload file")
              }
            } else {
              //TODO: Check permissions
              // Figure out which file we are replacing
              // First get the resource
              ResourceController.getResource(resourceId).flatMap { json =>
                val resource = json \ "resource"
                  
                // Now find the file
                val url = ((resource \ "content" \ "files")(0) \ "downloadUri").as[String]
                val name = url.substring(url.lastIndexOf("/") + 1)
                    
                // Replace the file
                FileUploader.uploadFile(file, name, mime).flatMap { url =>
                  // Handle updating the information.
                  val updatedFile = (resource \ "content" \ "files")(0).as[JsObject] ++ Json.obj(
                      "bytes" -> size
                  )
                  val updatedResource = resource.as[JsObject] ++ Json.obj(
                    "title" -> label,
                    "type" -> "document",
                    "languages" -> Json.obj(
                      "iso639_3" -> languages
                    ),
                    "content" -> Json.obj("files" -> List(updatedFile))
                  )
                  ResourceController.updateResource(resourceId, updatedResource)
                    .flatMap { response =>
					  val resource = (response \ "resource").as[JsObject]
                      AdditionalDocumentAdder.edit(content, resourceId, 'captionTrack, Json.obj("kind" -> kind)) { 
                        _ => Ok(resource) 
                      }
                    }.recover { case e =>
                      Logger.debug("Could not update resource: " + e.getMessage())
                      InternalServerError("Could not update resource")
                    }
                }.recover { case e =>
                  Logger.debug("Could not replace file: " + e.getMessage())
                  InternalServerError("Could not replace file")
                }
              }.recover { case e =>
                Logger.debug("Could not access resource: " + e.getMessage())
                InternalServerError("Could not access resource")
              }
            }
          }.getOrElse {
            Future(BadRequest)
          }
        }
    }
}
