package controllers

import authentication.Authentication
import play.api.mvc.{RequestHeader, Result, Request, Controller}
import models._
import service.FileUploader
import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import play.api.Logger
import dataAccess.ResourceController
import play.api.libs.json.{Json, JsValue}

/**
 * Controller for Administration pages and actions
 */
object Administration extends Controller {

  /**
   * Admin dashboard view
   */
  def admin = Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        Authentication.enforcePermission("admin") {
          Future(Ok(views.html.admin.dashboard()))
        }
  }

  /**
   * Request approval view
   */
  def approvalPage = Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        Authentication.enforcePermission("admin") {
          val requests = SitePermissionRequest.list
          Future(Ok(views.html.admin.permissionRequests(requests)))
        }
  }

  /**
   * Approves a request
   * @param id The ID of the request
   */
  def approveRequest() = Authentication.authenticatedAction(parse.multipartFormData) {
    implicit request =>
      implicit user =>
        Authentication.enforcePermission("admin") {
          for( id <- request.body.dataParts("reqid");
               req <- SitePermissionRequest.findById(id.toLong)
          ) { req.approve() }
          Future(Ok)
        }
  }

  /**
   * Denies a request
   * @param id The ID of the request
   */
  def denyRequest() = Authentication.authenticatedAction(parse.multipartFormData) {
    implicit request =>
      implicit user =>
        Authentication.enforcePermission("admin") {
          for( id <- request.body.dataParts("reqid");
               req <- SitePermissionRequest.findById(id.toLong)
          ) { req.deny(); }
          Future(Ok)
        }
  }

  /**
   * User management view
   */
  def manageUsers = Authentication.authenticatedAction() {
    implicit request => 
      implicit user =>
        Authentication.enforcePermission("admin") {
          Future(Ok(views.html.admin.users()))
        }
  }

  /**
   * Get Users {limit} at a time
   * @param id The id for the last user currently loaded on the page 
   * @param limit The size of the list of users queried from the db
   * @return list of user JSON objects
   */
  def pagedUsers(id: Long, limit: Long) = Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
      //check if admin
      Authentication.enforcePermission("admin") {
        Future(Ok(Json.toJson(User.listPaginated(id, limit).map(_.toJson))))
      }
  }

  /**
   * Helper function for finding user accounts
   * @param id The ID of the user account
   * @param f The function which will be called with the user
   */
  def getUser(id: Long)(f: User => Future[Result])(implicit request: RequestHeader): Future[Result] = {
    User.findById(id).map { user =>
      f(user.getAccountLink.flatMap(_.getPrimaryUser).getOrElse(user))
    }.getOrElse(Future(Errors.notFound))
  }

  /**
   * Give permissions to a user
   */
  def setPermission(operation: String = "") = Authentication.authenticatedAction(parse.multipartFormData) {
    implicit request =>
      implicit user =>
        Authentication.enforcePermission("admin") {
          val data = request.body.dataParts
          getUser(data("userId")(0).toLong) { targetUser =>
            operation match {
              case "remove" =>
                data("permission").foreach { permission =>
                  targetUser.removeSitePermission(permission)
                }
              case "match" =>
                targetUser.removeAllSitePermissions
                data("permission").foreach { permission =>
                  targetUser.addSitePermission(permission)
                }
              case _ =>
                data("permission").foreach { permission =>
                  targetUser.addSitePermission(permission)
                }
            }
            Future {
              Redirect(routes.Administration.manageUsers())
                .flashing("info" -> "User permissions updated")
            }
          }
        }
  }

  /**
   * Sends a notification to a user
   */
  def sendNotification(currentPage: Int) = Authentication.authenticatedAction(parse.urlFormEncoded) {
    //There may be a better way to control the way the user is redirected than with an Integer...
    implicit request =>
      implicit user =>
        Authentication.enforcePermission("admin") {
          val id = request.body("userId")(0).toLong
          getUser(id) { targetUser =>

            // Send a notification to the user
            val message = request.body("message")(0)
            targetUser.sendNotification(message)

            Future {
              Redirect(if(currentPage == 0) {
                routes.Administration.manageUsers()
              } else if (currentPage == 1) {
                routes.Administration.manageCourses()
              } else {
                routes.Application.home
              }).flashing("info" -> "Notification sent to user")
            }
          }
        }
  }

  /**
   * Deletes a user
   * @param id The ID of the user
   */
  def delete(id: Long) = Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        Authentication.enforcePermission("admin") {
          getUser(id) { targetUser =>
            targetUser.delete()
            Future {
              Redirect(routes.Administration.manageUsers())
                .flashing("info" -> "User deleted")
            }
          }
        }
  }

  /**
   * The course management view
   */
  def manageCourses = Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        Authentication.enforcePermission("admin") {
          val courses = Course.list
          Future(Ok(views.html.admin.courses(courses)))
        }
  }

  /**
   * Updates the name and enrollment of the course
   * @param id The ID of the course
   */
  def editCourse(id: Long) = Authentication.authenticatedAction(parse.urlFormEncoded) {
    implicit request =>
      implicit user =>
        Authentication.enforcePermission("admin") {
          Courses.getCourse(id) { course =>
            // Update the course
            val params = request.body.mapValues(_(0))
            course.copy(
			  name = params("name"),
			  enrollment = Symbol(params("enrollment")),
			  featured = (params("status") == "featured")
			).save
            Future {
              Redirect(routes.Administration.manageCourses())
                .flashing("info" -> "Course updated")
            }
          }
        }
  }

  /**
   * Deletes a course
   * @param id The ID of the course to delete
   */
  def deleteCourse(id: Long) = Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        Courses.getCourse(id) { course =>
          Future {
            if (user.hasCoursePermission(course, "deleteCourse")) {
              course.delete()
              Redirect(routes.Application.home)
                .flashing("info" -> "Course deleted")
            } else if(user.hasSitePermission("admin")) {
              course.delete()
              Redirect(routes.Administration.manageCourses())
                .flashing("info" -> "Course deleted")
            } else Errors.forbidden
          }
      }
  }

  /**
   * The content management view
   */
  def manageContent = Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        Authentication.enforcePermission("admin") {
          val content = Content.ownershipList
          Future(Ok(views.html.admin.content(content, ResourceController.baseUrl)))
        }
  }

  /**
   * Updates the settings of multiple content items
   */
  def batchUpdateContent = Authentication.authenticatedAction(parse.urlFormEncoded) {
    implicit request =>
      implicit user =>
        Authentication.enforcePermission("admin") {
          val redirect = Redirect(routes.Administration.manageContent())
          try {
            val params = request.body.mapValues(_(0))
            val shareability = params("shareability").toInt
            val visibility = params("visibility").toInt

            for(id <- params("ids").split(",") if !id.isEmpty;
                content <- Content.findById(id.toLong)) {
              content.copy(shareability = shareability, visibility = visibility).save
            }
            Future(redirect.flashing("info" -> "Contents updated"))
          } catch {
            case e: Throwable =>
              Logger.debug("Batch Update Error: " + e.getMessage())
              Future(redirect.flashing("error" -> s"Error while updating: ${e.getMessage()}"))
          }
        }
  }

  /**
   * The home page content view
   */
  def homePageContent = Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        Authentication.enforcePermission("admin") {
          Future(Ok(views.html.admin.homePageContent()))
        }
  }

  /**
   * Creates new home page content
   */
  def createHomePageContent = Authentication.authenticatedAction(parse.multipartFormData) {
    implicit request =>
      implicit user =>
        Authentication.enforcePermission("admin") {
          val redirect = Redirect(routes.Administration.homePageContent())
          Future {
            try {
              val data = request.body.dataParts.mapValues(_(0))
              val homePageContent = HomePageContent(None,
                data("title"),
                data("text"),
                data("link"),
                data("linkText"),
                data("background"),
                active = false
              )

              if (data("background").isEmpty) {
                val file = request.body.file("file").get
                val url = Await.result(FileUploader.uploadFile(file), Duration.Inf)
                homePageContent.copy(background = url).save
              } else {
                homePageContent.save
              }

              redirect.flashing("info" -> "Home page content created")
            } catch {
              case _ : Throwable =>
                redirect.flashing("error" -> "Failed to create home page content")
            }
          }
        }
  }

  /**
   * Toggles a particular home page content
   * @param id The ID of the home page content
   */
  def toggleHomePageContent(id: Long) = Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        Authentication.enforcePermission("admin") {
          Future {
            HomePageContent.findById(id).map { homePageContent =>
              homePageContent.copy(active = !homePageContent.active).save
              val message =
                if (homePageContent.active) "no longer active."
                else "now active."
              Redirect(routes.Administration.homePageContent())
                .flashing("info" -> ("Home page content is " + message))
            }.getOrElse {
              Errors.notFound
            }
          }
        }
  }

  /**
   * Deletes a particular home page content
   * @param id The ID of the home page content
   */
  def deleteHomePageContent(id: Long) = Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        Authentication.enforcePermission("admin") {
          Future {
            HomePageContent.findById(id).map { homePageContent =>
              homePageContent.delete()
              Redirect(routes.Administration.homePageContent())
                .flashing("info" -> "Home page content deleted")
            }.getOrElse {
              Errors.notFound
            }
          }
        }
  }

  /**
   * The site settings view
   */
  def siteSettings = Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        Authentication.enforcePermission("admin") {
          Future(Ok(views.html.admin.settings(Setting.list)))
        }
  }

  /**
   * Saves and updates the site settings
   */
  def saveSiteSettings = Authentication.authenticatedAction(parse.urlFormEncoded) {
    implicit request =>
      implicit user =>
        Authentication.enforcePermission("admin") {
          request.body.mapValues(_(0)).foreach { data => 
            Setting.findByName(data._1).get.copy(value = data._2).save
            Logger.debug(data._1 + ": " + data._2)
          }
          Future {
            Redirect(routes.Administration.siteSettings())
              .flashing("info" -> "Settings updated")
          }
        }
  }

  /**
   * Proxies in as a different user
   * @param id The ID of the user to be proxied in as
   */
  def proxy(id: Long) = Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        Authentication.enforcePermission("admin") {
          Future {
            User.findById(id) match {
            case Some(proxyUser) =>
              Redirect(routes.Application.home())
                .withSession("userId" -> id.toString)
                .flashing("info" -> s"You are now using the site as ${proxyUser.displayName}. To end proxy you must log out then back in with your normal account.")
            case _ =>
              Redirect(routes.Application.home())
                .flashing("info" -> ("Requested Proxy User Not Found"))
            }
          }
        }
  }
}
