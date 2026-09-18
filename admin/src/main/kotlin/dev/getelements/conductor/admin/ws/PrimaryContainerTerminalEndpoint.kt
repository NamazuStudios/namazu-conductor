package dev.getelements.conductor.admin.ws

import jakarta.websocket.OnClose
import jakarta.websocket.OnError
import jakarta.websocket.OnMessage
import jakarta.websocket.OnOpen
import jakarta.websocket.Session
import jakarta.websocket.server.PathParam
import jakarta.websocket.server.ServerEndpoint

/**
 * Attaches to a job's primary container's stdio: `ws://.../service/{jobId}`. The connection is
 * authorized by the first WebSocket text frame the client sends, not a query parameter — see
 * [TerminalSessionHandler] for the shared session logic and [ContainerTerminalEndpoint] for the
 * per-container equivalent.
 */
@ServerEndpoint("/service/{jobId}")
class PrimaryContainerTerminalEndpoint {

    @OnOpen
    fun onOpen(session: Session, @PathParam("jobId") jobId: String) =
        TerminalSessionHandler.onOpen(session, jobId, containerId = null)

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
