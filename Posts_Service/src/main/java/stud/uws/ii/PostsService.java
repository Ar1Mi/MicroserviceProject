package stud.uws.ii;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.*;
import java.util.*;

public class PostsService {

    private static String dbUrl;
    private static String dbUsername;
    private static String dbPassword;
    private static int servicePort;

    static {
        loadConfig();
    }

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(servicePort)) {
            System.out.println("Posts Service uruchomiono w porcie " + servicePort);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleRequest(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("Nie udało się uruchomić Posts Service: " + e.getMessage());
        }
    }

    private static void handleRequest(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

            String jsonRequest = in.readLine();
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> request = objectMapper.readValue(jsonRequest, HashMap.class);

            Map<String, Object> response;
            String action = (String) request.get("action");
            if ("add_post".equals(action)) {
                Map<String, String> content = (Map<String, String>) request.get("content");
                String username = content.get("username");
                String password = content.get("password");
                String title = content.get("title");
                String postContent = content.get("content");

                if (isUserLoggedIn(username, password)) {
                    if (addPost(username, title, postContent)) {
                        response = createResponse(request, "success", "Post added successfully.");
                    } else {
                        response = createResponse(request, "error", "Failed to add post.");
                    }
                } else {
                    response = createResponse(request, "error", "User not logged in.");
                }
            } else if ("get_all_posts".equals(action)) {
                String username = ((Map<String, String>) request.get("content")).get("username");
                String password = ((Map<String, String>) request.get("content")).get("password");

                if (isUserLoggedIn(username, password)) {
                    List<Map<String, Object>> posts = getAllPosts();
                    response = createResponse(request, "success", "Posts retrieved successfully.");
                    response.put("posts", posts);
                } else {
                    response = createResponse(request, "error", "User not logged in.");
                }
            } else {
                response = createResponse(request, "error", "Invalid action.");
            }

            String jsonResponse = objectMapper.writeValueAsString(response);
            out.println(jsonResponse);

        } catch (IOException e) {
            System.err.println("Błąd podczas przetwarzania żądania: " + e.getMessage());
        }
    }

    private static List<Map<String, Object>> getAllPosts() {
        List<Map<String, Object>> posts = new ArrayList<>();

        try (Connection connection = DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT p.id, p.user_id, p.title, p.content, p.created_at, u.username AS user_name" +
                     " FROM posts p JOIN users u ON p.user_id = u.id;\n")) {

            while (resultSet.next()) {
                Map<String, Object> post = new HashMap<>();
                post.put("id", resultSet.getInt("id"));
                post.put("user_id", resultSet.getInt("user_id"));
                post.put("user_name", resultSet.getString("user_name"));
                post.put("title", resultSet.getString("title"));
                post.put("content", resultSet.getString("content"));
                post.put("created_at", resultSet.getTimestamp("created_at").toString());
                posts.add(post);
            }

        } catch (SQLException e) {
            System.err.println("Błąd podczas pobierania postów: " + e.getMessage());
        }

        return posts;
    }

    private static boolean isUserLoggedIn(String username, String password) {
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
             PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM users WHERE username = ? AND password = ?")) {

            stmt.setString(1, username);
            stmt.setString(2, password);

            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1) > 0;

        } catch (SQLException e) {
            System.err.println("Błąd podczas pracy z bazą danych: " + e.getMessage());
            return false;
        }
    }

    private static boolean addPost(String username, String title, String content) {
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
             PreparedStatement userStmt = connection.prepareStatement("SELECT id FROM users WHERE username = ?");
             PreparedStatement postStmt = connection.prepareStatement("INSERT INTO posts (user_id, title, content) VALUES (?, ?, ?)")) {

            userStmt.setString(1, username);
            ResultSet rs = userStmt.executeQuery();
            if (rs.next()) {
                int userId = rs.getInt("id");

                postStmt.setInt(1, userId);
                postStmt.setString(2, title);
                postStmt.setString(3, content);
                postStmt.executeUpdate();
                return true;
            }

        } catch (SQLException e) {
            System.err.println("Błąd podczas dodawania wpisu do bazy danych: " + e.getMessage());
        }
        return false;
    }



    private static Map<String, Object> createResponse(Map<String, Object> request, String status, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("type", "response");
        response.put("id", request.get("id"));
        response.put("status", status);
        response.put("message", message);
        return response;
    }

    private static void loadConfig() {
        Properties properties = new Properties();
        try (InputStream input = new FileInputStream("config.properties")) {
            properties.load(input);
            dbUrl = properties.getProperty("database.url");
            dbUsername = properties.getProperty("database.username");
            dbPassword = properties.getProperty("database.password");
            servicePort = Integer.parseInt(properties.getProperty("posts.service.port"));
        } catch (IOException e) {
            System.err.println("Błąd ładowania konfiguracji: " + e.getMessage());
        }
    }
}
