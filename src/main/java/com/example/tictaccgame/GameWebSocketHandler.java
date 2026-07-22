package com.example.tictaccgame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles WebSocket connections for tic-tac-toe.
 *
 * Client -> Server message shapes (JSON):
 *   {"type":"join", "room":"ABCD"}
 *   {"type":"move", "room":"ABCD", "index":4}
 *   {"type":"restart", "room":"ABCD"}
 *
 * Server -> Client message shapes (JSON):
 *   {"type":"assigned", "mark":"X"}
 *   {"type":"state", "board":[...], "turn":"X", "winner":null}
 *   {"type":"error", "message":"..."}
 *   {"type":"opponent-left"}
 */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    // Track which room + mark each session belongs to, for cleanup on disconnect
    private final Map<WebSocketSession, String> sessionRoom = new ConcurrentHashMap<>();

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode json = mapper.readTree(message.getPayload());
        String type = json.path("type").asText();

        switch (type) {
            case "join" -> handleJoin(session, json);
            case "move" -> handleMove(session, json);
            case "restart" -> handleRestart(session, json);
            default -> sendError(session, "Unknown message type: " + type);
        }
    }

    private void handleJoin(WebSocketSession session, JsonNode json) throws IOException {
        String roomId = json.path("room").asText();
        if (roomId.isBlank()) {
            sendError(session, "Room code is required");
            return;
        }
        GameRoom room = rooms.computeIfAbsent(roomId, GameRoom::new);
        String mark = room.addPlayer(session);

        if (mark == null) {
            sendError(session, "Room '" + roomId + "' is full");
            return;
        }

        sessionRoom.put(session, roomId);

        ObjectNode assigned = mapper.createObjectNode();
        assigned.put("type", "assigned");
        assigned.put("mark", mark);
        assigned.put("room", roomId);
        session.sendMessage(new TextMessage(mapper.writeValueAsString(assigned)));

        broadcastState(room);
    }

    private void handleMove(WebSocketSession session, JsonNode json) throws IOException {
        String roomId = json.path("room").asText();
        int index = json.path("index").asInt(-1);
        GameRoom room = rooms.get(roomId);
        if (room == null) {
            sendError(session, "Room not found");
            return;
        }

        String mark = session.equals(room.getPlayerX()) ? "X"
                : session.equals(room.getPlayerO()) ? "O" : null;
        if (mark == null) {
            sendError(session, "You are not part of this room");
            return;
        }

        boolean applied = room.applyMove(index, mark);
        if (!applied) {
            sendError(session, "Invalid move");
            return;
        }

        broadcastState(room);
    }

    private void handleRestart(WebSocketSession session, JsonNode json) throws IOException {
        String roomId = json.path("room").asText();
        GameRoom room = rooms.get(roomId);
        if (room == null) {
            sendError(session, "Room not found");
            return;
        }
        room.reset();
        broadcastState(room);
    }

    private void broadcastState(GameRoom room) throws IOException {
        ObjectNode state = mapper.createObjectNode();
        state.put("type", "state");
        var boardArray = state.putArray("board");
        for (String cell : room.getBoard()) {
            boardArray.add(cell); // Jackson writes null for Java null, which is fine
        }
        state.put("turn", room.getTurn());
        state.put("winner", room.checkWinner());
        state.put("playersConnected", (room.getPlayerX() != null ? 1 : 0) + (room.getPlayerO() != null ? 1 : 0));

        String payload = mapper.writeValueAsString(state);
        TextMessage textMessage = new TextMessage(payload);

        if (room.getPlayerX() != null && room.getPlayerX().isOpen()) {
            room.getPlayerX().sendMessage(textMessage);
        }
        if (room.getPlayerO() != null && room.getPlayerO().isOpen()) {
            room.getPlayerO().sendMessage(textMessage);
        }
    }

    private void sendError(WebSocketSession session, String message) throws IOException {
        ObjectNode error = mapper.createObjectNode();
        error.put("type", "error");
        error.put("message", message);
        session.sendMessage(new TextMessage(mapper.writeValueAsString(error)));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String roomId = sessionRoom.remove(session);
        if (roomId == null) return;

        GameRoom room = rooms.get(roomId);
        if (room == null) return;

        room.removePlayer(session);

        // Notify the remaining player, if any
        WebSocketSession remaining = room.getPlayerX() != null ? room.getPlayerX() : room.getPlayerO();
        if (remaining != null && remaining.isOpen()) {
            ObjectNode left = mapper.createObjectNode();
            left.put("type", "opponent-left");
            remaining.sendMessage(new TextMessage(mapper.writeValueAsString(left)));
        } else {
            // Room is empty, clean it up
            rooms.remove(roomId);
        }
    }
}
