package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.*;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.util.EntityUtils;

public class MainApp extends JFrame {
    private JButton startButton;
    private JTextArea logArea;
    private boolean isRunning = false;
    private JLabel statusLabel;
    private Set<Integer> processedTaskIds = new HashSet<>();

    public MainApp() {
        setTitle("Task Processor");
        setSize(800, 600);

        startButton = new JButton("Запустить обработку");
        logArea = new JTextArea();
        statusLabel = new JLabel("Ожидание..."); // Создаем метку статуса
        statusLabel.setForeground(Color.BLACK);
        statusLabel.setHorizontalAlignment(SwingConstants.LEFT);

        startButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (isRunning) {
                    stopProcessing();
                    startButton.setText("Запустить обработку");
                    statusLabel.setText("Ожидание...");
                } else {
                    isRunning = true;
                    startButton.setText("Остановить обработку");
                    statusLabel.setText("Обработка запущена");
                    processTasks();
                }
            }
        });


        JPanel panel = new JPanel(new BorderLayout());
        panel.add(startButton, BorderLayout.NORTH);
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH); // Добавляем метку статуса внизу
        setContentPane(panel);
    }


    private int remainingTime = 60; // начальное значение в секундах
    private javax.swing.Timer timer; // указываем полное имя класса

    private void processTasks() {
        if (!isRunning) return;

        // Запускаем обработку в отдельном потоке
        new Thread(() -> {
            HttpClient client = null;
            try {
                String auth = "Basic YWRtaW46N2ovSy04TGJCPw==";
                String baseUrl = "https://test1232.intraservice.ru/api/task";

                ObjectMapper mapper = new ObjectMapper();
                mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

                client = HttpClientBuilder.create().build();

                while (isRunning) {
                    try {
                        // Обновляем статус в потоке GUI
                        SwingUtilities.invokeLater(() -> {
                            statusLabel.setText("Получение списка задач... " + new Date());
                        });

                        HttpGet getRequest = new HttpGet(baseUrl + "?StatusIds=31&fields=Id,Name,StatusId,Creator,ExecutorIds");
                        getRequest.setHeader("Authorization", auth);

                        HttpResponse response = client.execute(getRequest);
                        String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");

                        if (response.getStatusLine().getStatusCode() == 200) {
                            TaskList tasks = mapper.readValue(responseBody, TaskList.class);

                            // Обновляем статус в потоке GUI
                            SwingUtilities.invokeLater(() -> {
                                statusLabel.setText("Обработка задач (" + tasks.tasks.size() + " задач)");
                            });

                            for (Task task : tasks.tasks) {
                                if (!isRunning) return;

                                // Получаем ExecutorIds из текущей задачи
                                String executorIds = task.getExecutorIds();
                                String comment = task.getComment();

                                if (executorIds == null || executorIds.isEmpty() &&
                                        (comment == null || !comment.contains("Заявка в работе"))) {

                                    try {
                                        updateStatus("Обработка задачи №" + task.getId());
                                        updateTask(client, auth, task.getId(), executorIds, comment);
                                        processedTaskIds.add(task.getId());
                                    } catch (Exception e) {
                                        logArea.append("Ошибка обработки задачи №" + task.getId() + ": " + e.getMessage() + "\n");
                                    }
                                } else {
                                    SwingUtilities.invokeLater(() -> {
                                        logArea.append("Задача №" + task.getId() + " уже имеет исполнителя или комментарий, пропускаем\n");
                                    });
                                }
                            }
                        }
                        // Запускаем обратный отсчет
                        startCountdown();
                        // Ждем минуту перед следующей итерацией
                        Thread.sleep(60000);
                    } catch (IOException | InterruptedException e) {
                        SwingUtilities.invokeLater(() -> {
                            logArea.append("Ошибка: " + e.getMessage());
                            statusLabel.setText("Ошибка при получении данных");
                        });
                    }
                }
            } finally {
                client.getConnectionManager().shutdown();
                stopCountdown();
            }
        }).start();
    }

    private void startCountdown() {
        remainingTime = 60; // сбрасываем время
        updateStatus("Ожидание следующей проверки (" + remainingTime + " секунд)");

        // Создаем таймер с ActionListener
        timer = new javax.swing.Timer(1000, (ActionEvent e) -> {
            SwingUtilities.invokeLater(() -> {
                if (remainingTime > 0) {
                    remainingTime--;
                    updateStatus("Ожидание следующей проверки (" + remainingTime + " секунд)");
                } else {
                    stopCountdown();
                    processTasks(); // запускаем следующую проверку
                }
            });
        });

        timer.start(); // запускаем таймер
    }

    private void stopCountdown() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    private void updateStatus(String message) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(message);
        });
    }

    private void updateTask(HttpClient client, String auth, int taskId, String executorIds, String comment) {
        // Добавляем параметр executorIds для проверки
        if (!isRunning || executorIds != null && !executorIds.isEmpty() ||
                comment != null && comment.contains("Заявка в работе")) {
            return; // Если есть исполнитель или нужный комментарий, выходим
        }
        String url = "https://test1232.intraservice.ru/api/task/" + taskId;

        String body = "{\n" +
                "\"ExecutorIds\": \"329\",\n" +
                "\"StatusId\": \"27\",\n" +
                "\"Comment\": \"Заявка в работе\",\n" +
                "\"IsPrivateComment\": false\n" +
                "}";

        HttpPut putRequest = new HttpPut(url);
        putRequest.setHeader("Authorization", auth);
        putRequest.setHeader("Content-Type", "application/json");
        putRequest.setEntity(new StringEntity(body, "UTF-8"));

        try {
            HttpResponse response = client.execute(putRequest);

            if (response.getStatusLine().getStatusCode() == 200) {
                SwingUtilities.invokeLater(() -> {
                    logArea.append("Успешно обработана задача №" + taskId + "\n");
                    statusLabel.setText("Задача №" + taskId + " обработана успешно");
                });
            } else {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Ошибка при обработке задачи №" + taskId);
                    logArea.append("Ошибка: " + response.getStatusLine() + "\n");
                });
            }
        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> {
                logArea.append("Ошибка обработки задачи " + taskId + ": " + e.getMessage());
            });
        } finally {
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Ожидание следующей задачи...");
            });
        }
    }

    // Добавим метод для корректной остановки
    public void stopProcessing() {
        isRunning = false;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
// Классы для работы с JSON ответом
    public static class Task {
        @JsonProperty("Id")
        private int Id;
        @JsonProperty("Name")
        private String Name;
        @JsonProperty("StatusId")
        private int StatusId;
        @JsonProperty("Creator")
        private String Creator;
        @JsonProperty("ExecutorIds")
        private String ExecutorIds;
        @JsonProperty("Comment")
        private String Comment; // Добавляем поле для комментария

        public int getId() { return Id; }
        public String getName() { return Name; }
        public int getStatusId() { return StatusId; }
        public String getCreator() { return Creator; }
        public String getExecutorIds() { return ExecutorIds; }
        public String getComment() { return Comment; }

        // сеттеры можно добавить при необходимости
        public void setId(int id) { this.Id = id; }
        public void setName(String name) { this.Name = name; }
        public void setStatusId(int statusId) { this.StatusId = statusId; }
        public void setCreator(String creator) { this.Creator = creator; }
        public void setExecutorIds(String executorIds) { this.ExecutorIds = executorIds; }
        public void setComment(String comment) { this.Comment = comment; }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskList {
        @JsonProperty("Tasks")
        private List<Task> tasks;
        @JsonProperty("Priorities")
        private List<Object> priorities;
        @JsonProperty("Services")
        private List<Object> services;

        public List<Task> getTasks() {
            return tasks;
        }
        public void setTasks(List<Task> tasks) {
            this.tasks = tasks;
        }
        public List<Object> getPriorities() {
            return priorities;
        }
        public void setPriorities(List<Object> priorities) {
            this.priorities = priorities;
        }
        public List<Object> getServices() {
            return services;
        }
        public void setServices(List<Object> Services) {
            this.services = Services;
        }
    }

    // Метод для парсинга JSON ответа
    private TaskList parseResponse(String responseBody) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(responseBody, TaskList.class);
        } catch (Exception e) {
            logArea.append("Ошибка парсинга JSON: " + e.getMessage());
            return null;
        }
    }

    // Основной метод для запуска приложения
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                e.printStackTrace();
            }
            MainApp app = new MainApp();
            app.setVisible(true);
        });
    }}