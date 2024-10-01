package com.example.plugins

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import java.time.Duration
import java.util.Date
import kotlin.random.Random

data class User(
    val id: String,
    val channel: SendChannel<Frame>
)

data class Room(
    val id: String,
    var currentPageUrl: String = "",
    val users: ArrayList<User> = arrayListOf()
) {
    val master: User get() = users.first()
    val slaves: List<User> get() = users.filter { it != master }
}

class RoomsController {
    private val rooms = arrayListOf<Room>()

    fun createUser(userId: String, channel: SendChannel<Frame>): User {
        return User(
            id = userId,
            channel = channel
        )
    }

    fun createRoom(user: User): Room {
        val roomId = "${user.id}-${Random.nextInt(-2555, 2555)}-${Date().time}"

        val room = Room(
            id = roomId,
            users = arrayListOf(user)
        )

        rooms.add(room)

        return room
    }

    fun findRoomByRoomId(roomId: String): Room? {
        return rooms.find { it.id == roomId }
    }

    fun findRoomByUserId(userId: String): Room? {
        return rooms.find { it.users.find { it.id == userId } != null }
    }
}

val roomsController = RoomsController()

@OptIn(DelicateCoroutinesApi::class)
fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    routing {
        webSocket("/ws") { // websocketSession
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val spl = frame.readText().split(' ')
                    val userId = spl[1]

                    when(spl[0]) {
                        "ping" -> {
                            println("ping from userId $userId")
                        }

                        "createRoom" -> {
                            val user = roomsController.createUser(userId, outgoing)
                            val room = roomsController.createRoom(user)

                            println("createRoom $userId")
                            outgoing.send(Frame.Text("joinedToRoom ${room.id}"))
                        }

                        "joinRoom" -> {
                            val roomId = spl[2]

                            val room = roomsController.findRoomByRoomId(roomId)
                            if(room == null) {
                                outgoing.send(Frame.Text("error ROOM_NOT_FOUND"))
                                close(CloseReason(CloseReason.Codes.NORMAL, "BYE"))
                                println("Room not found for roomId $roomId")
                                return@webSocket
                            }

                            val user = roomsController.createUser(userId, outgoing)
                            room.users.add(user)

                            println("joinedToRoom ${room.id}")
                            outgoing.send(Frame.Text("joinedToRoom ${room.id}"))
                        }

                        "updatePage" -> {
                            val pageUrl = spl[2]
                            val room = roomsController.findRoomByUserId(userId)

                            if(room == null) {
                                outgoing.send(Frame.Text("error ROOM_NOT_FOUND"))
                                close(CloseReason(CloseReason.Codes.NORMAL, "BYE"))
                                println("Room not found for userId $userId")
                                return@webSocket
                            }

                            room.currentPageUrl = pageUrl
                            println("Notif all ${room.slaves.size} slaves about new pageUrl $pageUrl")

                            // Notify slaves
                            room.slaves.forEach {
                                if(!it.channel.isClosedForSend) {
                                    it.channel.send(Frame.Text("syncSlavePage $pageUrl"))
                                }
                            }
                        }

                        "sync" -> {
                            val timeSeconds = spl[2]
                            val room = roomsController.findRoomByUserId(userId)

                            if(room == null) {
                                outgoing.send(Frame.Text("error ROOM_NOT_FOUND"))
                                close(CloseReason(CloseReason.Codes.NORMAL, "BYE"))
                                println("Room not found for userId $userId")
                                return@webSocket
                            }

                            println("Notif all ${room.slaves.size} slaves about sync time $timeSeconds")

                            // Notify slaves
                            room.slaves.forEach {
                                if(!it.channel.isClosedForSend) {
                                    it.channel.send(Frame.Text("syncSlave $timeSeconds"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
