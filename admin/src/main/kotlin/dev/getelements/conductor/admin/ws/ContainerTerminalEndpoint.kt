package dev.getelements.conductor.admin.ws

import jakarta.websocket.OnClose
import jakarta.websocket.OnError
import jakarta.websocket.OnMessage
import jakarta.websocket.OnOpen
import jakarta.websocket.Session
import jakarta.websocket.server.PathParam
import jakarta.websocket.server.ServerEndpoint

/**
 * Attaches to a specific container's stdio: `ws://.../service/{jobId}/{containerId}?ticket=...`. See
 * [TerminalSessionHandler] for the shared session logic and [PrimaryContainerTerminalEndpoint] for
 * the default-container equivalent.
 */
@ServerEndpoint("/service/{jobId}/{containerId}")
class ContainerTerminalEndpoint {

    @OnOpen
    fun onOpen(
        session: Session,
        @PathParam("jobId") jobId: String,
        @PathParam("containerId") containerId: String
    ) = TerminalSessionHandler.onOpen(session, jobId, containerId)

    @OnMessage
    fun onBinaryMessage(session: Session, data: ByteArray) =
        TerminalSessionHandler.onBinaryMessage(session, data)

    @OnMessage
    fun onTextMessage(session: Session, text: String) =
        TerminalSessionHandler.onTextMessage(session, text)

    @OnClose
    fun onClose(session: Session) =
        TerminalSessionHandler.onClose(session)

    @OnError
    fun onError(session: Session, throwable: Throwable) =
        TerminalSessionHandler.onError(session, throwable)

}
