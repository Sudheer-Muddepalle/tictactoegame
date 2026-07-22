package com.example.tictaccgame;

import org.springframework.web.socket.WebSocketSession;

/**
 * Holds the state for a single tic-tac-toe game: the board, the two
 * connected player sessions, and whose turn it is.
 */
public class GameRoom {

    private final String roomId;
    private final String[] board = new String[9]; // null = empty, "X" or "O"
    private WebSocketSession playerX;
    private WebSocketSession playerO;
    private String turn = "X"; // X always goes first

    public GameRoom(String roomId) {
        this.roomId = roomId;
    }

    public synchronized String addPlayer(WebSocketSession session) {
        if (playerX == null) {
            playerX = session;
            return "X";
        } else if (playerO == null) {
            playerO = session;
            return "O";
        }
        return null; // room full
    }

    public synchronized void removePlayer(WebSocketSession session) {
        if (session.equals(playerX)) {
            playerX = null;
        } else if (session.equals(playerO)) {
            playerO = null;
        }
    }

    public synchronized boolean isFull() {
        return playerX != null && playerO != null;
    }

    public synchronized boolean applyMove(int index, String mark) {
        if (checkWinner() != null) return false;
        if (index < 0 || index > 8) return false;
        if (!mark.equals(turn)) return false;       // not this player's turn
        if (board[index] != null) return false;     // cell already taken

        board[index] = mark;
        turn = mark.equals("X") ? "O" : "X";
        return true;
    }

    public synchronized void reset() {
        for (int i = 0; i < board.length; i++) board[i] = null;
        turn = "X";
    }

    public synchronized String[] getBoard() {
        return board.clone();
    }

    public synchronized String getTurn() {
        return turn;
    }

    public String getRoomId() {
        return roomId;
    }

    public WebSocketSession getPlayerX() {
        return playerX;
    }

    public WebSocketSession getPlayerO() {
        return playerO;
    }

    /** Returns "X", "O", "DRAW", or null if the game is still in progress. */
    public synchronized String checkWinner() {
        int[][] lines = {
                {0,1,2}, {3,4,5}, {6,7,8}, // rows
                {0,3,6}, {1,4,7}, {2,5,8}, // columns
                {0,4,8}, {2,4,6}           // diagonals
        };
        for (int[] line : lines) {
            String a = board[line[0]], b = board[line[1]], c = board[line[2]];
            if (a != null && a.equals(b) && b.equals(c)) {
                return a;
            }
        }
        for (String cell : board) {
            if (cell == null) return null; // empty cells remain, game continues
        }
        return "DRAW";
    }
}
