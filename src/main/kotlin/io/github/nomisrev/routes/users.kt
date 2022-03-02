package io.github.nomisrev.routes

import arrow.core.Either
import arrow.core.computations.either
import arrow.core.computations.ensureNotNull
import io.github.nomisrev.jwtAuth
import io.github.nomisrev.service.UserService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelineContext
import kotlinx.serialization.Serializable

@Serializable data class NewUserRequest(val user: NewUser)

@Serializable data class NewUser(val username: String, val email: String, val password: String)

@Serializable
data class User(
  val email: String,
  val token: String,
  val username: String,
  val bio: String,
  val image: String
)

@Serializable data class UserResponse(val user: User)

@Serializable data class LoginUser(val email: String, val password: String)

@Serializable data class LoginUserRequest(val user: LoginUser)

fun Application.userRoutes(userService: UserService) = routing {
  route("/users") {
    /* Registration: POST /api/users */
    post {
      val res =
        either<ApiError, UserResponse> {
          val (username, email, password) =
            Either.catch { call.receive<NewUserRequest>() }
              .mapLeft { GenericErrorModel(it.message ?: "Received malformed JSON for NewUser") }
              .bind()
              .user
          val userId = userService.register(username, email, password).toGenericError().bind()
          val token = userService.generateJwtToken(userId, password).toGenericError().bind()
          UserResponse(User(email, token, username, "", ""))
        }
      respond(res, HttpStatusCode.Created)
    }
    post("/login") {
      val res =
        either<ApiError, UserResponse> {
          val (email, password) =
            Either.catch { call.receive<LoginUserRequest>() }
              .mapLeft {
                GenericErrorModel(it.message ?: "Received malformed JSON for LoginUserRequest")
              }
              .bind()
              .user
          val (userId, info) = userService.login(email, password).toGenericError().bind()
          val token = userService.generateJwtToken(userId, password).toGenericError().bind()
          UserResponse(User(email, token, info.username, info.bio, info.image))
        }
      respond(res, HttpStatusCode.OK)
    }
  }

  /* Get Current User: GET /api/user */
  get("/user") {
    jwtAuth(userService) { jwtContext ->
      val res =
        either<ApiError, UserResponse> {
          val user = userService.getUser(jwtContext.userId).toGenericError().bind()
          ensureNotNull(user) { GenericErrorModel("User not found") }
          UserResponse(User(user.email, jwtContext.token, user.username, user.bio, user.image))
        }
      respond(res, HttpStatusCode.OK)
    }
  }
}

fun <A> Either<UserService.Error, A>.toGenericError(): Either<ApiError, A> = mapLeft {
  when (it) {
    UserService.IncorrectLoginCredentials -> Unauthorized
    else -> it.toGenericErrorModel()
  }
}

suspend inline fun <reified A : Any> PipelineContext<Unit, ApplicationCall>.respond(
  either: Either<ApiError, A>,
  status: HttpStatusCode
): Unit =
  when (either) {
    is Either.Left ->
      when (either.value) {
        is Unauthorized -> call.respond(HttpStatusCode.Unauthorized)
        else -> call.respond(HttpStatusCode.UnprocessableEntity, either.value)
      }
    is Either.Right -> call.respond(status, either.value)
  }

/** Playing with Context Receivers */
// Fails to compile
// context(EitherEffect<ApiError, *>)
// suspend fun <A> Either<UserService.Error, A>.bind(): A =
//    toGenericError().bind()

// Fails at runtime :(
context(PipelineContext<Unit, ApplicationCall>)

@JvmName("respondContextReceiver")
suspend inline fun <reified A : Any> Either<ApiError, A>.respond(status: HttpStatusCode): Unit =
  respond(this@respond, status)
