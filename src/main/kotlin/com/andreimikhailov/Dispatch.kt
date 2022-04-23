package com.andreimikhailov

import com.andreimikhailov.formats.*
import com.andreimikhailov.models.*

import com.andreimikhailov.utils.authCreds
import com.andreimikhailov.utils.getCSRFTokenFromCookie
import com.andreimikhailov.utils.getUserFromCookie
import org.http4k.core.*
import org.http4k.core.ContentType.Companion.TEXT_HTML
import org.http4k.core.Method.GET
import org.http4k.core.Status.Companion.FORBIDDEN
import org.http4k.core.Status.Companion.SEE_OTHER
import org.http4k.core.Status.Companion.OK
import org.http4k.filter.ServerFilters
import org.http4k.format.Jackson
import org.http4k.format.Jackson.json
import org.http4k.lens.*
import org.http4k.lens.Query
import org.http4k.routing.ResourceLoader.Companion.Directory
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.static
import org.http4k.server.Http4kServer
import org.http4k.server.SunHttp
import org.http4k.server.asServer
import org.http4k.template.Jade4jTemplates
import org.http4k.template.ViewModel
import org.http4k.template.viewModel
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.*
import java.nio.file.Paths
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import java.util.Base64
import org.springframework.security.crypto.bcrypt.BCrypt
import java.time.LocalDate
import java.time.LocalDate.now
import java.time.LocalDateTime
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatter.ofLocalizedDateTime
import java.time.format.FormatStyle


val mkAuthFn: (YamlConfigCommon, YamlConfigInstance, Database) -> (Credentials) -> Boolean = { common, instance, db ->
    { creds ->
        transaction(db) {
            val schema = UsersTableSchema(instance.tableOfUsers)
            val u = schema.users.select { schema.users.login.eq(creds.user) }.toList()
            if (u.isEmpty()) {
                false
            } else {
                u.forEach {
                    //println("checking password")
                    //println("${it[schema.users.login]}:${it[schema.users.password]}")
                    if (BCrypt.checkpw(creds.password, it[schema.users.password])) {
                        return@transaction true;
                    }
                }
            }
            false
        }
    }
}
val csrfField = FormField.string().required("csrf")
val rptWeeksField = FormField.int().optional("repeatWeeks")
val descriptionField = FormField.string().required("description")
val linkField = FormField.string().required("link")
val startDateField = FormField.localDate().required("startDate")
val startTimeField = FormField.localTime().required("startTime")
val showToGroupField = FormField.int().required("showToGroup")
val showToAllField = FormField.int().required("showToAll")
val newPasswordField = FormField.string().required("password")

val strictAddFormBody = Body.webForm(
    //Validator.Feedback,
    Validator.Strict,
    csrfField,
    startDateField,
    startTimeField,
    descriptionField,
    linkField,
    rptWeeksField,
    showToGroupField,
    showToAllField
).toLens()

val idField = FormField.int().required("id")

val strictDelFormBody = Body.webForm(
    Validator.Strict,
    csrfField,
    idField
).toLens()

val strictNewPasswordBody = Body.webForm(
    Validator.Strict,
    csrfField,
    newPasswordField
).toLens()

private fun AuthServer(common: YamlConfigCommon, instance: YamlConfigInstance, db: Database): Http4kServer {
    val keyGen = KeyGenerator.getInstance("AES");
    keyGen.init(256); // for example
    val key: SecretKey = keyGen.generateKey();
    val encryptor: Cipher = Cipher.getInstance("AES");
    encryptor.init(Cipher.ENCRYPT_MODE, key);
    val decryptor: Cipher = Cipher.getInstance("AES");
    decryptor.init(Cipher.DECRYPT_MODE, key);

    val schemaOfEvents = EventsTableSchema(instance.tableOfEvents)
    val schemaOfUsers = UsersTableSchema(instance.tableOfUsers)

    fun <T : HttpMessage> mkview(mo: ViewModel): (T) -> T {
        val renderer = Jade4jTemplates().CachingClasspath()
        val view = Body.viewModel(renderer, TEXT_HTML).toLens()
        return view.of(mo)
    }


    fun relevantDateTimes(dayFrom: LocalDate, dayUntil: LocalDate, ev: EventData): List<LocalDateTime> {
        return (0..ev.repeatWeeks).mapNotNull {
            val d = ev.startDate.plusDays(7 * it.toLong())
            if (d.isAfter(dayFrom) && d.isBefore(dayUntil)) {
                LocalDateTime.of(d, ev.startTime)
            } else null
        }
    }

    fun prepareToShow(situation: Situation): (EventData) -> List<EventDataToShow> {
        val now = now()
        return { ev: EventData ->
            relevantDateTimes(situation.dayFrom.minusDays(1), situation.dayUntil.plusDays(1), ev).map { ldt ->
                EventDataToShow(
                    ev.id,
                    ev.owner,
                    ldt,
                    ldt.format(ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.MEDIUM)),
                    ev.repeatWeeks,
                    situation.user?.let {
                        if (ev.owner == it) {
                            ev.description
                        } else {
                            when (ev.showToGroup) {
                                Visibility.HIDE -> ""
                                Visibility.BUSY -> "busy"
                                Visibility.SHOW -> ev.description
                            }
                        }
                    } ?: when (ev.showToAll) {
                        Visibility.HIDE -> ""
                        Visibility.BUSY -> "busy"
                        Visibility.SHOW -> ev.description
                    },
                    situation.user?.let {
                        if (ev.owner == it) {
                            ev.link
                        } else {
                            when (ev.showToGroup) {
                                Visibility.HIDE -> ""
                                Visibility.BUSY -> ""
                                Visibility.SHOW -> ev.link
                            }
                        }
                    } ?: when (ev.showToAll) {
                        Visibility.HIDE -> ""
                        Visibility.BUSY -> ""
                        Visibility.SHOW -> ev.link
                    },
                    vis2int(ev.showToGroup),
                    vis2int(ev.showToAll),
                    Period.between(now, ldt.toLocalDate()).days + 30 * Period.between(now, ldt.toLocalDate()).months
                )
            }
        }
    }

    val eventsAsJSON: HttpHandler = { req ->
        val dayFromQuery = Query.string().optional("from")
        val dayUntilQuery = Query.string().optional("until")
        val dayFrom = dayFromQuery(req)?.let { LocalDate.parse(it) } ?: now().minusDays(1)
        val dayUntil = dayUntilQuery(req)?.let { LocalDate.parse(it) } ?: now().plusDays(3)
        val evs = getUserFromCookie(decryptor, key, req)?.let { user ->
            transaction(db) {
                val u: List<ResultRow> = schemaOfEvents.events.select {
                    schemaOfEvents.events.owner.eq(user).or(
                        schemaOfEvents.events.showToAll.greater(intLiteral(0))
                    ).or(
                        schemaOfEvents.events.showToGroup.greater(intLiteral(0))
                    )
                }.toList()
                u.map { eventData(it, schemaOfEvents) }
                    .flatMap(prepareToShow(Situation(user, dayFrom, dayUntil)))
                    .sortedBy { it.startDateTime }
                    .map { eventAsJson(it, schemaOfEvents, instance) }
            }
        } ?: transaction(db) {
            val u: List<ResultRow> = schemaOfEvents.events.select {
                schemaOfEvents.events.showToAll.greater(intLiteral(0))
            }.toList()
            u.map { eventData(it, schemaOfEvents) }
                .flatMap(prepareToShow(Situation(null, dayFrom, dayUntil)))
                .sortedBy { it.startDateTime }
                .map { eventAsJson(it, schemaOfEvents, instance) }
        }
        Response(OK).with(
            Body.json().toLens() of Jackson.array(evs)
        )
    }

    val haloMain: HttpHandler = { req ->
        val dayFromQuery = Query.string().optional("from")
        val dayUntilQuery = Query.string().optional("until")
        val dayFrom = dayFromQuery(req)?.let { LocalDate.parse(it) } ?: now().minusDays(1)
        val dayUntil = dayUntilQuery(req)?.let { LocalDate.parse(it) } ?: now().plusDays(3)
        val user = getUserFromCookie(decryptor, key, req)
        if (user == null) {
            val csrf = getCSRFTokenFromCookie(decryptor, key, req) ?: ""
            Response(OK).with(
                mkview(
                    HaloMainViewModel(
                        instance.urlPath,
                        csrf,
                        user,
                        dayFrom.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                        dayUntil.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                        instance.top ?: ""
                    )
                )
            )
        } else {
            val csrf = getCSRFTokenFromCookie(decryptor, key, req) ?: ""
            Response(OK).with(
                mkview(
                    HaloMainViewModel(
                        instance.urlPath,
                        csrf,
                        user,
                        dayFrom.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                        dayUntil.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                        instance.top ?: ""
                    )
                )
            )
        }
    }

    val neweventGET: HttpHandler = {
        val user = getUserFromCookie(decryptor, key, it);
        val csrf = getCSRFTokenFromCookie(decryptor, key, it)
        if (user == null || csrf == null) {
            Response(FORBIDDEN).with(mkview(MessageModel("you are not logged in", instance.urlPath)))
        } else {
            Response(OK).with(mkview(NewEventModel(instance.urlPath, csrf)))
        }
    }
    val neweventPOST: HttpHandler = {
        val validForm = strictAddFormBody(it)
        val tokenFromCookie = getCSRFTokenFromCookie(decryptor, key, it)
        val tokenFromForm = csrfField(validForm)
        //println("CSRF: >>>$tokenFromCookie<<< vs >>>$tokenFromForm<<<")
        getUserFromCookie(decryptor, key, it)?.let { user ->
            if (tokenFromCookie != tokenFromForm) {
                val renderer = Jade4jTemplates().CachingClasspath()
                val view = Body.viewModel(renderer, TEXT_HTML).toLens()
                Response(FORBIDDEN).with(view of MessageModel("bad CSRF token", instance.urlPath))
            } else {
                transaction(db) {
                    Events(instance.tableOfEvents).insert {
                        it[schemaOfEvents.events.owner] = user
                        it[schemaOfEvents.events.description] = descriptionField(validForm)
                        it[schemaOfEvents.events.startDate] = startDateField(validForm)
                        it[schemaOfEvents.events.startTime] = startTimeField(validForm)
                        it[schemaOfEvents.events.repeatWeeks] = rptWeeksField(validForm) ?: 0
                        it[schemaOfEvents.events.link] = linkField(validForm)
                        it[schemaOfEvents.events.showToGroup] = showToGroupField(validForm)
                        it[schemaOfEvents.events.showToAll] = showToAllField(validForm)
                    }
                }
                Response(SEE_OTHER).header("Location", instance.urlPath)
            }
        } ?: Response(SEE_OTHER).header("Location", instance.urlPath + "login")
    }
    val deleventGET: HttpHandler = {
        val idQuery = Query.int().required("id")
        val descriptionQuery = Query.string().required("description")
        val dateTimeQuery = Query.dateTime(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")).required("datetime")
        val user = getUserFromCookie(decryptor, key, it)
        val csrf = getCSRFTokenFromCookie(decryptor, key, it)
        if (user == null) {
            Response(FORBIDDEN).with(mkview(MessageModel("you are not logged in", instance.urlPath)))
        } else if (csrf == null) {
            Response(FORBIDDEN).with(mkview(MessageModel("CSRF error", instance.urlPath)))
        } else if (
            transaction(db) {
                schemaOfEvents.events.select { schemaOfEvents.events.id.eq(idQuery(it)) }.toList()
            }.any { x -> (eventData(x, schemaOfEvents).owner == user) }
        ) {
            Response(OK).with(
                mkview(
                    DelEventModel(
                        instance.urlPath,
                        csrf,
                        idQuery(it),
                        descriptionQuery(it),
                        dateTimeQuery(it).toString()
                    )
                )
            )
        } else {
            Response(FORBIDDEN).with(mkview(MessageModel("you are not the owner of this event", instance.urlPath)))
        }
    }

    val deleventPOST: HttpHandler = {
        val validForm = strictDelFormBody(it)
        val tokenFromCookie = getCSRFTokenFromCookie(decryptor, key, it)
        val tokenFromForm = csrfField(validForm)
        //println("CSRF: >>>$tokenFromCookie<<< vs >>>$tokenFromForm<<<")
        getUserFromCookie(decryptor, key, it)?.let { user ->
            if (tokenFromCookie != tokenFromForm) {
                Response(FORBIDDEN).with(mkview(MessageModel("bad or missing CSRF token", instance.urlPath)))
            } else {
                val isOwner = transaction(db) {
                    schemaOfEvents.events.select { schemaOfEvents.events.id.eq(idField(validForm)) }.toList()
                }.any { x -> (eventData(x, schemaOfEvents).owner == user) }
                if (isOwner) {
                    transaction(db) {
                        Events(instance.tableOfEvents).deleteWhere {
                            schemaOfEvents.events.id eq idField(validForm)
                        }
                    }
                    Response(SEE_OTHER).header("Location", instance.urlPath)
                } else {
                    Response(FORBIDDEN).with(
                        mkview(MessageModel("you cannot DELETE this event because you have not created it", instance.urlPath))
                    )
                }
            }
        } ?: Response(SEE_OTHER).header("Location", instance.urlPath + "login")
    }
    val editeventGET: HttpHandler = { req ->
        val userM = getUserFromCookie(decryptor, key, req)
        val tokenFromCookie = getCSRFTokenFromCookie(decryptor, key, req)
        val csrfQuery = Query.string().required("csrf")
        val idQuery = Query.int().required("id")
        if (tokenFromCookie != csrfQuery(req)) {
            val renderer = Jade4jTemplates().CachingClasspath()
            val view = Body.viewModel(renderer, TEXT_HTML).toLens()
            Response(FORBIDDEN).with(view of MessageModel("bad CSRF token", instance.urlPath))
        } else {
            val eventsToEdit = transaction(db) {
                schemaOfEvents.events.select {
                    schemaOfEvents.events.id.eq(idQuery(req))
                        .and(userM?.let { user -> schemaOfEvents.events.owner.eq(user) } ?: booleanLiteral(false))
                }.toList()
            }
            if (eventsToEdit.isEmpty()) {
                Response(FORBIDDEN).with(
                    mkview(
                        MessageModel("user $userM is not allowed to edit this event", instance.urlPath)
                    )
                )
            } else {
                Response(OK)
                    .with(
                        mkview(
                            EditEventModel(
                                instance.urlPath,
                                tokenFromCookie,
                                eventDataToEdit(eventsToEdit.first(), schemaOfEvents)
                            )
                        )
                    )
            }
        }
    }
    val editeventPOST: HttpHandler = { req ->
        val tokenFromCookie = getCSRFTokenFromCookie(decryptor, key, req)
        val validForm = strictDelFormBody(req)
        val tokenFromForm = csrfField(validForm)
        getUserFromCookie(decryptor, key, req)?.let { user ->
            if (tokenFromCookie != tokenFromForm) {
                val renderer = Jade4jTemplates().CachingClasspath()
                val view = Body.viewModel(renderer, TEXT_HTML).toLens()
                Response(FORBIDDEN).with(view of MessageModel("bad CSRF token", instance.urlPath))
            } else {
                val isOwner = transaction(db) {
                    schemaOfEvents.events.select { schemaOfEvents.events.id.eq(idField(validForm)) }.toList()
                }.any { x -> (eventData(x, schemaOfEvents).owner == user) }
                if (isOwner) {
                    transaction(db) {
                        Events(instance.tableOfEvents).update({
                            schemaOfEvents.events.id eq idField(validForm)
                        }) {
                            it[schemaOfEvents.events.description] = descriptionField(validForm)
                            it[schemaOfEvents.events.owner] = user
                            it[schemaOfEvents.events.startDate] = startDateField(validForm)
                            it[schemaOfEvents.events.startTime] = startTimeField(validForm)
                            it[schemaOfEvents.events.repeatWeeks] = rptWeeksField(validForm) ?: 0
                            it[schemaOfEvents.events.link] = linkField(validForm)
                            it[schemaOfEvents.events.showToGroup] = showToGroupField(validForm)
                            it[schemaOfEvents.events.showToAll] = showToAllField(validForm)
                        }
                    }
                    Response(SEE_OTHER).header("Location", instance.urlPath)
                } else {
                    val renderer = Jade4jTemplates().CachingClasspath()
                    val view = Body.viewModel(renderer, TEXT_HTML).toLens()
                    Response(FORBIDDEN).with(
                        view of MessageModel("you cannot UPDATE this event because you have not created it", instance.urlPath)
                    )
                }
            }
        } ?: Response(SEE_OTHER).header("Location", instance.urlPath + "login")
    }
    val changepasswordGET: HttpHandler = { req ->
        val userM = getUserFromCookie(decryptor, key, req)
        val tokenFromCookie = getCSRFTokenFromCookie(decryptor, key, req)
        if (tokenFromCookie == null) {
            val renderer = Jade4jTemplates().CachingClasspath()
            val view = Body.viewModel(renderer, TEXT_HTML).toLens()
            Response(FORBIDDEN).with(view of MessageModel("bad CSRF token", instance.urlPath))
        } else {
            Response(OK).with(mkview(ChangePasswordModel(instance.urlPath, tokenFromCookie)))
        }
    }
    val changepasswordPOST: HttpHandler = { req ->
        val tokenFromCookie = getCSRFTokenFromCookie(decryptor, key, req)
        val validForm = strictNewPasswordBody(req)
        val tokenFromForm = csrfField(validForm)
        val salt = BCrypt.gensalt()
        val newPassword = BCrypt.hashpw(newPasswordField(validForm),salt)
        getUserFromCookie(decryptor, key, req)?.let { user ->
            if (tokenFromCookie != tokenFromForm) {
                val renderer = Jade4jTemplates().CachingClasspath()
                val view = Body.viewModel(renderer, TEXT_HTML).toLens()
                Response(FORBIDDEN).with(view of MessageModel("bad CSRF token", instance.urlPath))
            } else {
                transaction(db) {
                    Users(instance.tableOfUsers).update({
                        schemaOfUsers.users.login eq user
                    }) { it[schemaOfUsers.users.password] = newPassword }
                }
                Response(OK).with(mkview(MessageModel("password changed", instance.urlPath)))
            }
        } ?: Response(SEE_OTHER).header("Location", instance.urlPath + "login")
    }
    val setCookie: HttpHandler = {
        val creds: Credentials? = it.authCreds();
        val user = creds!!.user;
        //println("Registering user: $user");
        val encryptedUser = Base64.getEncoder().encodeToString(encryptor.doFinal(user.encodeToByteArray()));
        //println("Encrypted user: $encryptedUser");
        val decryptedUser = String(decryptor.doFinal(Base64.getDecoder().decode(encryptedUser)));
        println("Registered user: $decryptedUser");
        val charPool: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val randomString = (1..128)
            .map { i -> kotlin.random.Random.nextInt(0, charPool.size) }
            .map(charPool::get)
            .joinToString("");
        val encryptedCSRF = Base64.getEncoder().encodeToString(encryptor.doFinal(randomString.encodeToByteArray()));
        Response(SEE_OTHER)
            .header(
                "Set-Cookie",
                "user=$encryptedUser"
            )
            .header(
                "Set-Cookie",
                "csrf=$encryptedCSRF"
            )
            .header("Location", instance.urlPath)
    }

    return routes(
        "/login" bind GET to
                ServerFilters.BasicAuth("normal", mkAuthFn(common, instance, db))
                    .then(setCookie),
        "/ping" bind GET to {
            println("userFromCookie ${getUserFromCookie(decryptor, key, it)}");
            Response(OK).body("pong, ${getUserFromCookie(decryptor, key, it)}")
        },
        "/" bind GET to ServerFilters.CatchAll()(haloMain),
        "/static" bind static(Directory(instance.staticDir)),
        "/list" bind GET to ServerFilters.CatchAll()(eventsAsJSON),
        "/newevent" bind GET to ServerFilters.CatchAll()(neweventGET),
        "/newevent" bind Method.POST to ServerFilters.CatchAll()(neweventPOST),
        "/delevent" bind GET to ServerFilters.CatchAll()(deleventGET),
        "/delevent" bind Method.POST to ServerFilters.CatchAll()(deleventPOST),
        "/editevent" bind GET to ServerFilters.CatchAll()(editeventGET),
        "/editevent" bind Method.POST to ServerFilters.CatchAll()(editeventPOST),
        "/changepassword" bind GET to ServerFilters.CatchAll()(changepasswordGET),
        "/changepassword" bind Method.POST to ServerFilters.CatchAll()(changepasswordPOST)
    ).asServer(SunHttp(instance.localPort))
}

private fun parseArgs(args: Array<String>): Pair<String, String> {
    fun parse(xs: List<String>, commonConf: String?, instanceConf: String?): Pair<String, String> {
        val parsed: Pair<String, String> = if (xs.isEmpty()) Pair(commonConf!!, instanceConf!!) else when (xs[0]) {
            "-c" -> parse(xs.drop(2), xs[1], instanceConf)
            "-i" -> parse(xs.drop(2), commonConf, xs[1])
            else -> Pair(commonConf!!, instanceConf!!)
        }
        return parsed
    }
    return parse(args.toList(), null, null)
}

fun main(args: Array<String>) {
    val (commonConfFile, instanceConfFile) = parseArgs(args)
    val common = loadConfCommonFromFile(Paths.get(commonConfFile))
    val instance = loadConfInstanceFromFile(Paths.get(instanceConfFile))
    val dbURL = common.dbURL;
    val db: Database = Database.connect(
        url = dbURL,
        driver = "org.postgresql.Driver",
        user = common.dbLogin,
        password = common.dbPassword
    )
    val server = AuthServer(common, instance, db).start()
    println("Server started on " + server.port())
}
