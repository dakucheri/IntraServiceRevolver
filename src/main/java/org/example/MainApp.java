package org.example;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.ssl.TrustStrategy;
import org.apache.http.util.EntityUtils;

import javax.net.ssl.SSLContext;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class ConfigLoader {
    public static String getAuth() {
        String auth = System.getenv("APP_AUTH");
        if (auth == null || auth.isEmpty()) {
            throw new RuntimeException("Переменная окружения APP_AUTH не установлена");
        }
        return auth;
    }

    public static int getCheckIntervalMs() {
        String intervalStr = System.getenv("APP_CHECK_INTERVAL_MS");
        if (intervalStr == null || intervalStr.isEmpty()) {
            return 60000; // значение по умолчанию: 60 секунд
        }
        try {
            return Integer.parseInt(intervalStr);
        } catch (NumberFormatException e) {
            System.err.println("Некорректное значение APP_CHECK_INTERVAL_MS, используется значение по умолчанию");
            return 60000;
        }
    }

    public static Map<String, String> getLoginToExecutorId() {
        Map<String, String> result = new HashMap<>();
        Map<String, String> env = System.getenv();
        if (env != null) {  // Защита от null
            for (Map.Entry<String, String> entry : env.entrySet()) {
                if (entry.getKey().startsWith("LOGIN_TO_EXECUTOR_")) {
                    String login = entry.getKey().replace("LOGIN_TO_EXECUTOR_", "");
                    result.put(login, entry.getValue());
                }
            }
        }
        if (result.isEmpty()) {
            result.put("ikornilov", "319");
            result.put("employee-1", "329");
        }
        return result;
    }
}

public class MainApp {
    private static final String AUTH = ConfigLoader.getAuth();
    private static final String BASE_URL = "https://support2-sand.ffoms.gov.ru/api/task";
    private static final String ACTIVE_LINE_URL = "http://192.168.100.114/api/v1/line/active";
    private static final int CHECK_INTERVAL_MS = ConfigLoader.getCheckIntervalMs();
    private static final AtomicBoolean IS_RUNNING = new AtomicBoolean(false);
    private static int PROCESSED_TASK_N = 0; // пример счётчика
    private static final Set<Integer> PROCESSED_TASK_CASH = Collections.synchronizedSet(new HashSet<>());
    private static final Set<Integer> PROCESSED_TASK_IDS = Collections.synchronizedSet(new HashSet<>());
    private static final long CLEAR_CACHE_INTERVAL_MS = 24 * 60 * 60 * 1000L; // 24 часа
    private static long lastCacheClearTime = System.currentTimeMillis();
    private static final Map<String, String> LOGIN_TO_EXECUTOR_ID = ConfigLoader.getLoginToExecutorId();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final AtomicInteger currentEmployeeIndex = new AtomicInteger(0);
    private static volatile List<String> currentActiveEmployees = Collections.synchronizedList(new ArrayList<>());

    private static CloseableHttpClient createHttpClientTrustingAllCerts() {
        try {
            TrustStrategy trustStrategy = (certificates, authType) -> true;

            SSLContext sslContext = SSLContexts.custom()
                    .loadTrustMaterial(null, trustStrategy)
                    .build();

            SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(
                    sslContext, NoopHostnameVerifier.INSTANCE);

            return HttpClients.custom()
                    .setSSLSocketFactory(sslsf)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при создании HttpClient", e);
        }
    }

    static {
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static String getCurrentGmt3Time() {
        ZoneId gmt3 = ZoneId.of("Europe/Moscow");
        LocalDateTime now = LocalDateTime.now(gmt3);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
        return now.format(formatter);
    }

    private static void log(String level, String message) {
        String timestamp = getCurrentGmt3Time();
        String logMessage = String.format("[%s] [%s] %s", timestamp, level, message);
        System.out.println(logMessage); // Контейнеры захватывают stdout/stderr
    }

    private static void logError(String message, Throwable e) {
        log("ERROR", message + " — " + e.getClass().getSimpleName() + ": " + e.getMessage());
        e.printStackTrace(); // Полный стек вызовов в stderr
    }
//    static {
//        Map<String, String> tempMap = new HashMap<>();
//        tempMap.put("ikornilov", "319");
//        tempMap.put("employee-1", "329");
//        LOGIN_TO_EXECUTOR_ID = Collections.unmodifiableMap(tempMap);
//    }

    public static void main(String[] args) {
        log("INIT", "Application starting with Corretto 25...");
        System.out.println("[INIT] Application starting with Corretto 25...");

        startProcessing();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("SHUTDOWN", "Received stop signal. Stopping processing...");
            IS_RUNNING.set(false);
        }));


        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("[SHUTDOWN] Application interrupted");
        }

        System.out.println("[EXIT] Application stopped");
    }

    private static void startProcessing() {
        IS_RUNNING.set(true);
        Thread processingThread = new Thread(MainApp::processTasks);
        processingThread.setDaemon(false);
        processingThread.setName("TaskProcessor");
        processingThread.start();
        log("INFO", "Processing started on thread: " + processingThread.getName());
    }

    private static void printStatus() {
        System.out.println("Статус: " + (IS_RUNNING.get() ? "Обработка запущена" : "Обработка остановлена"));
        System.out.println("Обработано задач: " + PROCESSED_TASK_IDS);
    }

    private static void processTasks() {
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        try (CloseableHttpClient client = createHttpClientTrustingAllCerts()) {
            while (IS_RUNNING.get()) {
                try {
                    log("INFO", "Получение списка задач...");

                    // Проверка и очистка кэша раз в 24 часа
                    if (System.currentTimeMillis() - lastCacheClearTime >= CLEAR_CACHE_INTERVAL_MS) {
                        clearProcessedTasksCache();
                    }

                    String requestUrl = BASE_URL + "?StatusIds=43,31&fields=Id,Name,StatusId,Creator,ExecutorIds";
                    log("DEBUG", "HTTP GET запрос: " + requestUrl);

                    HttpGet getRequest = new HttpGet(requestUrl);
                    getRequest.setHeader("Authorization", AUTH);

                    HttpResponse response = client.execute(getRequest);
                    int statusCode = response.getStatusLine().getStatusCode();
                    String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");

                    log("DEBUG", "HTTP ответ: код " + statusCode + ", тело: " + responseBody);

                    if (statusCode == 200) {
                        TaskList tasks = mapper.readValue(responseBody, TaskList.class);
                        log("INFO", "Найдено задач: " + tasks.getTasks().size());

                        // Фильтруем задачи
                        List<Task> unprocessedTasks = new ArrayList<>();
                        for (Task task : tasks.getTasks()) {
                            if (!PROCESSED_TASK_IDS.contains(task.getId())) {
                                unprocessedTasks.add(task);
                            }
                        }
                        log("DEBUG", "Фильтрованных задач для обработки: " + unprocessedTasks.size());

                        // Получаем список сотрудников на смене
                        List<String> activeEmployees = getActiveEmployees(client, mapper);
                        log("DEBUG", "Активных сотрудников: " + activeEmployees.size() + ", список: " + activeEmployees);

                        // Обрабатываем только отфильтрованный список
                        for (Task task : unprocessedTasks) {
                            if (!IS_RUNNING.get()) break;
                            processTask(client, task, activeEmployees);
                        }
                    } else {
                        log("ERROR", "Ошибка HTTP: код " + statusCode +
                                ", сообщение: " + response.getStatusLine().getReasonPhrase());
                    }

                    waitForNextCheck();
                } catch (IOException e) {
                    logError("Ошибка при получении данных", e);
                    waitForNextCheck();
                }
            }
        } catch (Exception e) {
            logError("Критическая ошибка в процессе обработки", e);
        }
    }


    private static void waitForNextCheck() {
        try {
            log("DEBUG", "Ожидание " + CHECK_INTERVAL_MS + " мс до следующей проверки...");
            Thread.sleep(CHECK_INTERVAL_MS);
        } catch (InterruptedException e) {
            // Восстанавливаем статус прерывания
            Thread.currentThread().interrupt();
            log("INFO", "Ожидание прервано, продолжаем работу...");
        }
    }

    private static List<String> getActiveEmployees(CloseableHttpClient client, ObjectMapper mapper) {
        try {
            log("DEBUG", "Запрос списка активных сотрудников: " + ACTIVE_LINE_URL);
            HttpGet getRequest = new HttpGet(ACTIVE_LINE_URL);
            HttpResponse response = client.execute(getRequest);

            int statusCode = response.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
            log("DEBUG", "Ответ активных сотрудников: код " + statusCode + ", тело: " + responseBody);

            if (statusCode == 200) {
                Employee[] employees = mapper.readValue(responseBody, Employee[].class);
                log("DEBUG", "Получено сотрудников: " + employees.length);

                List<String> result = new ArrayList<>();
                for (Employee employee : employees) {
                    String displayName = employee.getDisplayName();
                    if (LOGIN_TO_EXECUTOR_ID.containsKey(displayName)) {
                        result.add(displayName);
                        log("DEBUG", "Сотрудник " + displayName + " добавлен в обработку");
                    } else {
                        log("DEBUG", "Сотрудник " + displayName + " пропущен (нет в конфигурации)");
                    }
                }

                // Обновляем глобальный список и сбрасываем индекс при изменении состава
                synchronized (currentActiveEmployees) {
                    if (!currentActiveEmployees.equals(result)) {
                        currentActiveEmployees.clear();
                        currentActiveEmployees.addAll(result);
                        currentEmployeeIndex.set(0); // Сбрасываем счётчик при изменении списка
                        log("INFO", "Обновлён список активных сотрудников. Теперь: " + currentActiveEmployees);
                    }
                }
                return result;
            } else {
                log("ERROR", "Ошибка получения списка активных сотрудников: код " + statusCode);
            }
        } catch (Exception e) {
            logError("Исключение при получении активных сотрудников", e);
        }
        return Collections.emptyList();
    }


    private static void processTask(CloseableHttpClient client, Task task, List<String> activeEmployees) {
        int taskId = task.getId();
        String executorIds = task.getExecutorIds();

        log("DEBUG", "Обработка задачи №" + taskId +
                ", исполнитель: " + (executorIds != null ? executorIds : "отсутствует") +
                ", активных сотрудников: " + activeEmployees.size());

        // Пропускаем задачи с исполнителем и добавляем в кэш
        if (executorIds != null && !executorIds.isEmpty()) {
            log("SKIPPED", "Задача №" + taskId + " уже имеет исполнителя: " + executorIds);
            PROCESSED_TASK_IDS.add(taskId);
            return;
        }

        // Получаем актуальный список сотрудников
        List<String> employeesForAssignment = currentActiveEmployees;

        if (employeesForAssignment.isEmpty()) {
            log("WARNING", "Нет активных сотрудников для назначения задачи №" + taskId);
            return;
        }

        // Выбираем сотрудника по очереди
        int currentIndex = currentEmployeeIndex.getAndIncrement();
        int employeeIndex = currentIndex % employeesForAssignment.size();
        String selectedEmployeeLogin = employeesForAssignment.get(employeeIndex);
        String targetExecutorId = LOGIN_TO_EXECUTOR_ID.get(selectedEmployeeLogin);

        log("DEBUG", "Выбран сотрудник " + selectedEmployeeLogin +
                " (ID: " + targetExecutorId + ") для задачи №" + taskId +
                " (индекс в очереди: " + employeeIndex + ")");

        try {
            updateTask(client, taskId, targetExecutorId);
            log("SUCCESS", "Задача №" + taskId +
                    " назначена на сотрудника " + selectedEmployeeLogin + " (ID: " + targetExecutorId);
            PROCESSED_TASK_IDS.add(taskId);
        } catch (Exception e) {
            logError("Ошибка обновления задачи №" + taskId, e);
        }
    }

    private static void updateTask(HttpClient client, int taskId, String executorId) throws IOException {
        log("DEBUG", "Подготовка к обновлению задачи №" + taskId +
                " — назначение исполнителя ID: " + executorId);

        try {
            String updateUrl = BASE_URL + "/" + taskId;
            HttpPut putRequest = new HttpPut(updateUrl);
            putRequest.setHeader("Authorization", AUTH);
            putRequest.setHeader("Content-Type", "application/json");

            // Формируем тело запроса
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("ExecutorIds", executorId);
            requestBody.put("StatusId", "27");
            requestBody.put("Comment", "Здравствуйте. Информируем о том, что Ваша заявка в работе.");
            requestBody.put("IsPrivateComment", false);

            //ObjectMapper mapper = new ObjectMapper();
            String jsonBody = mapper.writeValueAsString(requestBody);
            log("DEBUG", "Тело запроса PUT для задачи №" + taskId + ": " + jsonBody);

            putRequest.setEntity(new StringEntity(jsonBody, "UTF-8"));

            HttpResponse response = client.execute(putRequest);
            int statusCode = response.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");

            log("DEBUG", "Ответ на обновление задачи №" + taskId +
                    ": код " + statusCode + ", тело: " + responseBody);

            if (statusCode != 200 && statusCode != 204) {
                throw new IOException("HTTP " + statusCode +
                        " при обновлении задачи №" + taskId + ": " + responseBody);
            }

            log("INFO", "Задача №" + taskId + " успешно обновлена (статус: " + statusCode + ")");
        } catch (Exception e) {
            logError("Критическая ошибка при обновлении задачи №" + taskId, e);
            throw e;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Employee {
        @JsonProperty("display_name")
        private String displayName;

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
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
        private String Comment;

        public int getId() {
            return Id;
        }

        public String getName() {
            return Name;
        }

        public int getStatusId() {
            return StatusId;
        }

        public String getCreator() {
            return Creator;
        }

        public String getExecutorIds() {
            return ExecutorIds;
        }

        public String getComment() {
            return Comment;
        }

        public void setId(int id) {
            this.Id = id;
        }

        public void setName(String name) {
            this.Name = name;
        }

        public void setStatusId(int statusId) {
            this.StatusId = statusId;
        }

        public void setCreator(String creator) {
            this.Creator = creator;
        }

        public void setExecutorIds(String executorIds) {
            this.ExecutorIds = executorIds;
        }

        public void setComment(String comment) {
            this.Comment = comment;
        }
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
            return tasks != null ? tasks : new ArrayList<>();
        }

        public void setTasks(List<Task> tasks) {
            this.tasks = tasks;
        }

        public List<Object> getPriorities() {
            return priorities != null ? priorities : new ArrayList<>();
        }

        public void setPriorities(List<Object> priorities) {
            this.priorities = priorities;
        }

        public List<Object> getServices() {
            return services != null ? services : new ArrayList<>();
        }

        public void setServices(List<Object> services) {
            this.services = services;
        }
    }
    private static void clearProcessedTasksCache() {
        int removedCount = PROCESSED_TASK_IDS.size();
        PROCESSED_TASK_IDS.clear();
        log("CACHE", "Очищено " + removedCount + " записей из кэша обработанных задач (ежедневная очистка)");
        lastCacheClearTime = System.currentTimeMillis();
    }
}