package models

import anorm._
import anorm.SqlParser._
import java.sql.SQLException
import dataAccess.sqlTraits._
import play.api.db.DB
import play.api.libs.json.{Json, JsValue}
import play.api.Play.current
import play.api.Logger
import controllers.routes
import service.{EmailTools, TimeTools}

/**
 * User
 * @param id The ID of the user.
 * @param authId ID returned from authentication
 * @param authScheme Which authentication scheme this user used
 * @param username The username (often the same as authId)
 * @param name A displayable name for the user
 * @param email The user's email address
 * @param role The permissions of the user
 */
case class User(id: Option[Long], authId: String, authScheme: Symbol, username: String,
                name: Option[String] = None, email: Option[String] = None,
                picture: Option[String] = None, accountLinkId: Long = -1,
                created: String = TimeTools.now(), lastLogin: String = TimeTools.now())
  extends SQLSavable with SQLDeletable {

  /**
   * Saves the user to the DB
   * @return The possibly updated user
   */
  def save =
    if (id.isDefined) {
      update(User.tableName,
        'authId -> authId, 'authScheme -> authScheme.name,
        'username -> username, 'name -> name, 'email -> email, 'picture -> picture,
        'accountLinkId -> accountLinkId, 'created -> created, 'lastLogin -> lastLogin
      )
      this
    } else {
      val id = insert(User.tableName,
        'authId -> authId, 'authScheme -> authScheme.name,
        'username -> username, 'name -> name, 'email -> email, 'picture -> picture,
        'accountLinkId -> accountLinkId, 'created -> created, 'lastLogin -> lastLogin
      )
      this.copy(id)
    }

  /**
   * Deletes the user from the DB
   */
  def delete() {  
    // Delete the user's content
    getContent.foreach(_.delete())

    DB.withConnection { implicit connection =>
      try {
        BatchSql(
          "delete from {table} where userId = {id}",
          List('table -> "courseMembership", 'id -> id.get),
          List('table -> "announcement", 'id -> id.get),
          List('table -> "notification", 'id -> id.get),
          List('table -> "addCourseRequest", 'id -> id.get),
          List('table -> "sitePermissionRequest", 'id -> id.get),
          List('table -> "sitePermissions", 'id -> id.get)
        )

        // Delete all linked accounts
        getAccountLink.map { accountLink =>
          if (accountLink.primaryAccount == id.get) {
            val params = accountLink.userIds.filterNot(_ == id.get)
              .map { uid =>  List(NamedParameter.symbol('id -> uid)) }
              .toList
            BatchSql(
              s"delete from $User.tableName where id = {id}",
              params.head, params.tail:_*
            ).execute()
          }
        }

      } catch {
        case e: SQLException =>
          Logger.debug("Failed to delete User data")
          Logger.debug(e.getMessage())
      }
    }

    delete(User.tableName)
  }


  //                  _   _
  //        /\       | | (_)
  //       /  \   ___| |_ _  ___  _ __  ___
  //      / /\ \ / __| __| |/ _ \| '_ \/ __|
  //     / ____ \ (__| |_| | (_) | | | \__ \
  //    /_/    \_\___|\__|_|\___/|_| |_|___/
  //
  //   ______ ______ ______ ______ ______ ______ ______ ______ ______
  // |______|______|______|______|______|______|______|______|______|
  //

  /**
   * Checks if a user is already enrolled in a course
   * @param course The course in which the user will be enrolled
   */
  def isEnrolled(course: Course) = CourseMembership.userIsEnrolled(this, course)

  /**
   * Enrolls the user in a course
   * @param course The course in which the user will be enrolled
   * @param teacher Is this user a teacher of the course?
   * @return The user (for chaining)
   */
  def enroll(course: Course, teacher: Boolean = false): User = {
    if(!this.isEnrolled(course))
      CourseMembership(None, id.get, course.id.get, teacher).save
    this
  }

  /**
   * Unenroll the user from a course
   * @param course The course from which to unenroll
   * @return The user (for chaining)
   */
  def unenroll(course: Course): User = {

    // First, find the membership
    val membership = CourseMembership.listByUser(this).filter(_.courseId == course.id.get)

    if (membership.size > 1)
      Logger.warn("Multiple (or zero) memberships for user #" + id.get + " in course #" + course.id.get)

    // Delete all found
    membership.foreach(_.delete)

    this
  }

  /**
   * Create content from a resource and assign this user as the owner
   * @param content The content that will be owned
   * @return The content ownership
   */
  def addContent(content: Content): ContentOwnership =
    ContentOwnership(None, this.id.get, content.id.get).save

  /**
   * Submits a teacher request for this user
   * @param reason The reason for the request
   * @return The teacher request
   */
  def requestPermission(permission: String, reason: String): SitePermissionRequest = SitePermissionRequest(None, this.id.get, permission, reason).save

  /**
   * Sends a notification to this user
   * @param message The message of the notification
   * @return The notification
   */
  def sendNotification(message: String): Notification = {
    if (Setting.findByName("notifications.users.emailOn.notification").get.value == "true" && email.isDefined) {
      EmailTools.sendEmail(List((displayName, email.get)), "Ayamel notification") {
        s"You have received the following notification:\n\n$message"
      } {
        s"<p>You have received the following notification:</p><p>$message</p>"
      }
    }
    Notification(None, this.id.get, message).save
  }

  def addWord(word: String, srcLang: String, destLang: String): WordListEntry = WordListEntry(None, word, srcLang, destLang, id.get).save

  /**
   * Moves user ownership and enrollment from the provided user to the current user
   * @param user The user to move ownership
   */
  def consolidateOwnership(user: User) {
    val thisid = this.id.get

    // Transfer content ownership
    ContentOwnership.listByUser(user).foreach { _.copy(userId = thisid).save }

    // Move the notifications over
    user.getNotifications.foreach { _.copy(userId = thisid).save }

    // Move the announcements over
    Announcement.list.filter(_.userId == user.id.get).foreach { _.copy(userId = thisid).save }

    // Move the course membership over. Check this user's membership to prevent duplicates
    val myMembership = CourseMembership.listByUser(this).map(_.courseId)
    CourseMembership.listByUser(user).foreach { membership =>
      if (myMembership.contains(membership.courseId))
        membership.delete()
      else
        membership.copy(userId = thisid).save
    }

    // Merge permission requests
    val otherRequests = SitePermissionRequest.listByUser(user)
    val newRequests = (otherRequests.map(_.copy(userId = thisid)).toSet
      -- SitePermissionRequest.listByUser(this))

    // Merge permissions
    user.getPermissions.foreach { p => addSitePermission(p) }

    otherRequests.foreach { _.delete() }
    newRequests.foreach { _.save }
  }

  /**
   * Merges the provided user into this one.
   * @param user The user to merge
   */
  //TODO: Figure out how to properly deal with unchecked gets
  //They will only fail if data is corrupted, and it's not immediately clear
  //what should be done in those cases
  def merge(user: User) {
    val thisid = this.id.get

    /*
     * Three possibilities:
     * 1. Neither user has an account link
     * 2. One user has an account link
     * 3. Both users have an account link
     */
    if (this.accountLinkId == -1) {
      if (user.accountLinkId == -1) {
        // Case 1: Create a new account link
        val accountLink = AccountLink(None, Set(thisid, user.id.get), thisid).save

        val linkId = accountLink.id.get
        this.copy(accountLinkId = linkId).save
        user.copy(accountLinkId = linkId).save

        consolidateOwnership(user)
      } else {
        //Case 2: Make this the primary of the existing account link
        val accountLink = AccountLink.findById(user.accountLinkId).get

        accountLink.addUser(this)

        consolidateOwnership(accountLink.getPrimaryUser.get)

        this.copy(accountLinkId = accountLink.id.get).save
        accountLink.copy(primaryAccount = thisid).save
      }
    } else {
      //these branches can only be talen if this is the primary user
      if (user.accountLinkId == -1) {
        // Case 2: Add the other user to this account link
        val accountLink = AccountLink.findById(this.accountLinkId).get

        accountLink.addUser(user)

        consolidateOwnership(user)
      } else {
        // Case 3: Merge the other account link into this one
        val thisLink = AccountLink.findById(this.accountLinkId).get
        val userLink = AccountLink.findById(user.accountLinkId).get
        val newLink = thisLink.copy(userIds = thisLink.userIds ++ userLink.userIds, primaryAccount = thisid).save
        val linkId = newLink.id.get

        consolidateOwnership(userLink.getPrimaryUser.get)
        newLink.getUsers foreach { user => user.copy(accountLinkId = linkId).save }
        userLink.delete()
      }
    }
  }

  /**
   * Gets a string from an option.
   */
  def getStringFromOption(opt: Option[String]): String = opt.getOrElse("")

  /**
   * Gets all of the fields required for the Admin dashboard table
   */
  def toJson = Json.obj(
    "id" -> id.get,
    "authScheme" -> authScheme.name,
    "username" -> username,
    "name" -> getStringFromOption(name),
    "email" -> getStringFromOption(email),
    "linked" -> accountLinkId,
    "permissions" -> getPermissions
  )

  //       _____      _   _
  //      / ____|    | | | |
  //     | |  __  ___| |_| |_ ___ _ __ ___
  //     | | |_ |/ _ \ __| __/ _ \ '__/ __|
  //     | |__| |  __/ |_| ||  __/ |  \__ \
  //      \_____|\___|\__|\__\___|_|  |___/
  //
  //   ______ ______ ______ ______ ______ ______ ______ ______ ______
  // |______|______|______|______|______|______|______|______|______|
  //

  /**
   * Any items that are retrieved from the DB should be cached here in order to reduce the number of DB calls
   */
  val cacheTarget = this
  object cache {

    var enrollment: Option[List[Course]] = None

    def getEnrollment = {
      if (enrollment.isEmpty)
        enrollment = Some(CourseMembership.listUsersClasses(cacheTarget))
      enrollment.get
    }

    var teacherEnrollment: Option[List[Course]] = None

    def getTeacherEnrollment = {
      if (teacherEnrollment.isEmpty)
        teacherEnrollment = Some(CourseMembership.listTeacherClasses(cacheTarget))
      teacherEnrollment.get
    }

    var content: Option[List[Content]] = None

    def getContent = {
      if (content.isEmpty)
        content = Some(ContentOwnership.listUserContent(cacheTarget))
      content.get
    }

    var contentFeed: Option[List[(Content, Long)]] = None

    def getContentFeed = {
      if (contentFeed.isEmpty)
        contentFeed = Some(
          getEnrollment.flatMap(course => course.getContent.map(c => (c, course.id.get)))
            .sortWith((c1, c2) => TimeTools.dateToTimestamp(c1._1.dateAdded) > TimeTools.dateToTimestamp(c2._1.dateAdded))
        )
      contentFeed.get
    }

    var announcementFeed: Option[List[(Announcement, Course)]] = None

    def getAnnouncementFeed = {
      if (announcementFeed.isEmpty)
        announcementFeed = Some(
          getEnrollment.flatMap(course => course.getAnnouncements.map(announcement => (announcement, course)))
            .sortWith((d1, d2) => TimeTools.dateToTimestamp(d1._1.timeMade) > TimeTools.dateToTimestamp(d2._1.timeMade))
        )
      announcementFeed.get
    }

    var notifications: Option[List[Notification]] = None

    def getNotifications = {
      if (notifications.isEmpty)
        notifications = Some(Notification.listByUser(cacheTarget))
      notifications.get
    }

    var accountLink: Option[Option[AccountLink]] = None

    def getAccountLink = {
      if (accountLink.isEmpty)
        accountLink = Some(AccountLink.findById(accountLinkId))
      accountLink.get
    }

    var scorings: Option[List[Scoring]] = None

    def getScorings: List[Scoring] = {
      if (scorings.isEmpty)
        scorings = Some(Scoring.listByUser(cacheTarget))
      scorings.get
    }

    var wordList: Option[List[WordListEntry]] = None

    def getWordList: List[WordListEntry] = {
      if (wordList.isEmpty)
        wordList = Some(WordListEntry.listByUser(cacheTarget))
      wordList.get
    }

  }

  /**
   * Gets the enrollment--courses the user is in--of the user
   * @return The list of courses
   */
  def getEnrollment: List[Course] = cache.getEnrollment

  /**
   * Gets the courses this user is teaching
   * @return The list of courses
   */
  def getTeacherEnrollment: List[Course] = cache.getTeacherEnrollment

  /**
   * Gets the content belonging to this user
   * @return The list of content
   */
  def getContent: List[Content] = cache.getContent

  /**
   * Get the profile picture. If it's not set then return the placeholder picture.
   * @return The url of the picture
   */
  def getPicture: String = picture.getOrElse(routes.Assets.at("images/users/facePlaceholder.jpg").url)

  /**
   * Tries the user's name, if it doesn't exists then returns the username
   * @return A displayable name
   */
  def displayName: String = name.getOrElse(username)

  /**
   * Gets the latest content from this user's courses.
   * @param limit The number of content objects to get
   * @return The content
   */
  def getContentFeed(limit: Int = 5): List[(Content, Long)] = cache.getContentFeed.take(limit)

  /**
   * Gets the latest announcements made in this user's courses.
   * @param limit The number of announcement to get
   * @return The announcements paired with the course they came from
   */
  def getAnnouncementFeed(limit: Int = 5): List[(Announcement, Course)] = cache.getAnnouncementFeed.take(limit)

  /**
   * Gets a list of the user's notifications
   * @return
   */
  def getNotifications: List[Notification] = cache.getNotifications

  /**
   * Returns the account link
   * @return If it exists, then Some(AccountLink) otherwise None
   */
  def getAccountLink: Option[AccountLink] =
    if (accountLinkId == -1)
      None
    else
      cache.getAccountLink

  /**
   * Gets the list of this user's scorings
   * @return The list of scorings
   */
  def getScorings = cache.getScorings

  /**
   * Gets the list of this user's scorings for a particular content
   * @return The list of scorings
   */
  def getScorings(content: Content) = cache.getScorings.filter(_.contentId == content.id.get)

  def getWordList = cache.getWordList

  def getPermissions = SitePermissions.listByUser(this)

  def getCoursePermissions(course: Course) = course.getUserPermissions(this)

  def hasSitePermission(permission: String): Boolean =
    SitePermissions.userHasPermission(this, permission) || SitePermissions.userHasPermission(this, "admin")

  def hasCoursePermission(course: Course, permission: String): Boolean =
    course.userHasPermission(this, permission) || course.getTeachers.contains(this) || SitePermissions.userHasPermission(this, "admin")


  //       _____      _   _
  //      / ____|    | | | |
  //     | (___   ___| |_| |_ ___ _ __ ___
  //      \___ \ / _ \ __| __/ _ \ '__/ __|
  //      ____) |  __/ |_| ||  __/ |  \__ \
  //     |_____/ \___|\__|\__\___|_|  |___/
  //     ____ ______ ______ ______ ______ ______ ______ ______ ______
  // |______|______|______|______|______|______|______|______|______|
  //

  def addSitePermission(permission: String) =
    SitePermissions.addUserPermission(this, permission)

  def removeSitePermission(permission: String) =
    SitePermissions.removeUserPermission(this, permission)

  def removeAllSitePermissions =
    SitePermissions.removeAllUserPermissions(this)

  def addCoursePermission(course: Course, permission: String) =
    course.addUserPermission(this, permission)

  def removeCoursePermission(course: Course, permission: String) =
    course.removeUserPermission(this, permission)

  def removeAllCoursePermissions(course: Course) =
    course.removeAllUserPermissions(this)
}

object User extends SQLSelectable[User] {
  val tableName = "userAccount"

  val simple = {
    get[Option[Long]](tableName + ".id") ~
      get[String](tableName + ".authId") ~
      get[String](tableName + ".authScheme") ~
      get[String](tableName + ".username") ~
      get[Option[String]](tableName + ".name") ~
      get[Option[String]](tableName + ".email") ~
      get[Option[String]](tableName + ".picture") ~
      get[Long](tableName + ".accountLinkId") ~
      get[String](tableName + ".created") ~
      get[String](tableName + ".lastLogin") map {
      case id ~ authId ~ authScheme ~ username ~ name ~ email ~ picture ~ accountLinkId ~ created ~ lastLogin => {
        val _name = if (name.isEmpty) None else name
        val _email = if (email.isEmpty) None else email
        User(id, authId, Symbol(authScheme), username, _name, _email, picture, accountLinkId, created, lastLogin)
      }
    }
  }

  /**
   * Search the DB for a user with the given id.
   * @param id The id of the user.
   * @return If a user was found, then Some[User], otherwise None
   */
  def findById(id: Long): Option[User] = findById(id, simple)

  /**
   * Search the DB for a user with the given authentication info
   * @param authId The id from the auth scheme
   * @param authScheme Which auth scheme
   * @return If a user was found, then Some[User], otherwise None
   */
  def findByAuthInfo(authId: String, authScheme: Symbol): Option[User] = {
    DB.withConnection { implicit connection =>
      try {
        SQL("select * from userAccount where authId = {authId} and authScheme = {authScheme}")
          .on('authId -> authId, 'authScheme -> authScheme.name)
          .as(simple.singleOpt)
      } catch {
        case e: SQLException =>
          Logger.debug("Failed in User.scala / findByAuthInfo")
          Logger.debug(e.getMessage())
          None
      }
    }
  }

  /**
   * Search the DB for a lti user with the given authentication info
   * @param name The user's name
   * @param email The user's email
   * @return If a user was found, then Some[User], otherwise None
   */
  def findLtiUserByNameAndEmail(name: Option[String], email: Option[String]): Option[User] = {
    if (email.isEmpty) {
      None
    } else {
      DB.withConnection { implicit connection =>
        try {
          SQL("select * from userAccount where authScheme = 'ltiAuth' and name = {name} and email = {email}")
            .on('name -> name.getOrElse(""), 'email -> email.get)
            .as(simple.singleOpt)
        } catch {
          case e: SQLException =>
            Logger.debug("Failed in User.scala / findLtiUserByNameAndEmail")
            Logger.debug(e.getMessage())
            None
        }
      }
    }
  }

  /**
   * Finds a user based on the username and the authScheme.
   * @param authScheme The auth scheme to search
   * @param username The username to look for
   * @return If a user was found, then Some[User], otherwise None
   */
  def findByUsername(authScheme: Symbol, username: String): Option[User] = {
    DB.withConnection { implicit connection =>
      try {
        SQL("select * from userAccount where authScheme = {authScheme} and username = {username}")
          .on('authScheme -> authScheme.name, 'username -> username)
          .as(simple.singleOpt)
      } catch {
        case e: SQLException =>
          Logger.debug("Failed in User.scala / findByUsername")
          Logger.debug(e.getMessage())
          None
      }
    }
  }

  /**
   * Gets all users in the DB
   * @return The list of users
   */
  def list: List[User] = list(simple)

  /**
   *
   */
  def listPaginated(id: Long, limit: Long): List[User] = {
    DB.withConnection {
      implicit connection =>
        try {
          SQL(s"select * from $tableName where id between {lowerBound} and {upperBound}")
            .on('lowerBound -> id, 'upperBound -> (id + limit))
            .as(simple *)
        } catch {
          case e: SQLException =>
            Logger.debug("Error getting paginated users. User.scala")
            Nil
        }
    }
  }

  /**
   * Create a user from fixture data
   * @param data Fixture data
   * @return The user
   */
  def fromFixture(data: (String, Symbol, String, Option[String], Option[String], Symbol)): User = {
    val user = User(None, data._1, data._2, data._3, data._4, data._5).save
    SitePermissions.assignRole(user, data._6)
    user
  }
}