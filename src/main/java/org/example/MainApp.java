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
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.ssl.TrustStrategy;
import org.apache.http.util.EntityUtils;

import javax.net.ssl.SSLContext;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

class ConfigLoader {
    public static String getAuth() {
        String auth = System.getenv("APP_AUTH");
        if (auth == null || auth.isEmpty()) {
            throw new RuntimeException("Переменная окружения APP_AUTH не установлена");
        }
        return auth;
    }

    public static int getParallelism() {
        String parallelismStr = System.getenv("APP_PARALLELISM");
        if (parallelismStr == null || parallelismStr.isEmpty()) {
            return 2;
        }
        try {
            return Integer.parseInt(parallelismStr);
        } catch (NumberFormatException e) {
            System.err.println("Некорректное значение APP_PARALLELISM, используется значение по умолчанию");
            return 2;
        }
    }

    public static int getCheckIntervalMs() {
        String intervalStr = System.getenv("APP_CHECK_INTERVAL_MS");
        if (intervalStr == null || intervalStr.isEmpty()) {
            return 60000;
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
        if (env != null) {
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
    private static final String BASE_URL = "https://10.255.183.3/api/task";
    private static final String ACTIVE_LINE_URL = "http://192.168.100.114/api/v1/line/active";
    private static final int CHECK_INTERVAL_MS = ConfigLoader.getCheckIntervalMs();
    private static final AtomicBoolean IS_RUNNING = new AtomicBoolean(false);
    private static final Set<Integer> PROCESSED_TASK_IDS = Collections.synchronizedSet(new HashSet<>());
    private static final long CLEAR_CACHE_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    private static long lastCacheClearTime = System.currentTimeMillis();
    private static final Map<String, String> LOGIN_TO_EXECUTOR_ID = ConfigLoader.getLoginToExecutorId();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final AtomicInteger currentEmployeeIndex = new AtomicInteger(0);
    private static volatile List<String> currentActiveEmployees = Collections.synchronizedList(new ArrayList<>());
    private static final List<AssignmentStats> assignmentHistory = Collections.synchronizedList(new ArrayList<>());
    private static final String STATS_FILE_PATH = "/app/stats/assignment_stats.csv";
    private static final String SUMMARY_FILE_PATH = "/app/stats/summary_stats.txt";
    private static final int PARALLELISM = ConfigLoader.getParallelism();
    private static final Object statsLock = new Object();
    private static final String WEEKLY_ARCHIVE_DIR = "/data/app_stats/archive";
    private static final long WEEK_IN_MILLIS = 7 * 24 * 60 * 60 * 1000L;
    private static long lastArchiveCheck = System.currentTimeMillis();
    private static final Map<LocalDate, Map<String, Integer>> dailyStats = new ConcurrentHashMap<>();

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
        long threadId = Thread.currentThread().getId();
        String logMessage = String.format("[%s] [Thread-%d] [%s] %s",
                timestamp, threadId, level, message);
        System.out.println(logMessage);
    }

    private static void printThreadStats() {
        synchronized (PROCESSED_TASK_IDS) {
            log("STATS", "Обработано задач: " + PROCESSED_TASK_IDS.size());
        }
    }

    private static void logError(String message, Throwable e) {
        log("ERROR", message + " — " + e.getClass().getSimpleName() + ": " + e.getMessage());
        e.printStackTrace();
    }

    public static void main(String[] args) {
        initializeStatsFiles();
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

    private static void logSeparator() {
        String separator = "─".repeat(80);
        System.out.println("\n" + separator);
    }

    private static void processTasks() {
        try (CloseableHttpClient client = createHttpClientTrustingAllCerts()) {
            while (IS_RUNNING.get()) {
                logSeparator();

                if (System.currentTimeMillis() - lastCacheClearTime >= CLEAR_CACHE_INTERVAL_MS) {
                    clearProcessedTasksCache();
                }

                // Сначала получаем активных сотрудников ОДИН раз на цикл
                List<String> activeEmployees = getActiveEmployees(client, mapper);
                log("INFO", "На этой итерации доступно активных сотрудников: " + activeEmployees.size()
                        + " -> " + activeEmployees);

                if (activeEmployees.isEmpty()) {
                    log("WARNING", "Нет активных сотрудников — пропускаем обработку задач до следующей итерации");
                    waitForNextCheck();
                    continue;
                }

                // --- ГРУППА 1: обычные задачи (все сервисы, кроме 47) ---
                processTasksGroup1(client, activeEmployees);

                // --- ГРУППА 2: сервис 47 и статус 27 — только наблюдатель + исполнитель ---
                processTasksGroup2(client, activeEmployees);

                checkAndArchiveWeeklyStats();
                waitForNextCheck();
            }
        } catch (Exception e) {
            logError("Критическая ошибка в процессе обработки", e);
        }
    }

    // Обычные задачи: все сервисы кроме 47
    private static void processTasksGroup1(CloseableHttpClient client, List<String> activeEmployees) {
        String requestUrl = BASE_URL + "?StatusIds=43,31&ServiceIds=64,63,62,61,50,49,46,48,45,42,41,44,43,32,22,21,20,17&fields=Id,Name,StatusId,Creator,ExecutorIds,ServiceId&PageSize=50";
        log("DEBUG", "HTTP GET (Group1): " + requestUrl);

        HttpGet getRequest = new HttpGet(requestUrl);
        getRequest.setHeader("Authorization", AUTH);

        List<Task> tasks = fetchTasks(client, getRequest);
        if (tasks.isEmpty()) {
            log("INFO", "Group1: задач не найдено");
            return;
        }

        List<Task> unprocessedTasks = tasks.stream()
                .filter(task -> !PROCESSED_TASK_IDS.contains(task.getId()))
                .collect(Collectors.toList());

        if (unprocessedTasks.isEmpty()) {
            log("INFO", "Group1: все найденные задачи уже обработаны");
            return;
        }

        unprocessedTasks.sort(Comparator.comparingInt(Task::getId));
        log("INFO", "Group1: найдено новых задач для обработки: " + unprocessedTasks.size());

        processTasksInParallel(unprocessedTasks, activeEmployees, client, false); // false = обычная логика
    }

    // Задачи: сервис 47 и статус 27 — только наблюдатель + исполнитель, с двойной проверкой стабильности
    private static void processTasksGroup2(CloseableHttpClient client, List<String> activeEmployees) {
        try {
            List<Task> stableTasks = getStableTasksForGroup2(client);

            if (stableTasks.isEmpty()) {
                log("INFO", "Group2: нет стабильных задач для обработки");
                return;
            }

            // Фильтрация по кэшу уже обработанных
            List<Task> unprocessedTasks = stableTasks.stream()
                    .filter(task -> !PROCESSED_TASK_IDS.contains(task.getId()))
                    .collect(Collectors.toList());

            if (unprocessedTasks.isEmpty()) {
                log("INFO", "Group2: все стабильные задачи уже обработаны");
                return;
            }

            unprocessedTasks.sort(Comparator.comparingInt(Task::getId));
            log("INFO", "Group2: найдено новых стабильных задач для обработки (сервис 47 и 19, статус 27): "
                    + unprocessedTasks.size());

            // true = упрощённая логика: только наблюдатель + исполнитель, без статуса и комментария
            processTasksInParallel(unprocessedTasks, activeEmployees, client, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logError("Прерывание при обработке Group2", e);
        }
    }

    private static List<Task> getStableTasksForGroup2(CloseableHttpClient client) throws InterruptedException {
        String requestUrl = BASE_URL + "?ServiceIds=47,19&StatusIds=27&fields=Id,Name,StatusId,Creator,ExecutorIds,ServiceId&PageSize=50";
        log("DEBUG", "HTTP GET (Group2, 1-й запрос): " + requestUrl);

        HttpGet getRequest1 = new HttpGet(requestUrl);
        getRequest1.setHeader("Authorization", AUTH);
        List<Task> firstBatch = fetchTasks(client, getRequest1);

        if (firstBatch.isEmpty()) {
            log("INFO", "Group2: в 1-м запросе задач не найдено — повтор не делаем");
            return Collections.emptyList();
        }

        // Ждём 5 секунд перед вторым запросом
        log("DEBUG", "Group2: ожидание 5000 мс перед повторным запросом для проверки стабильности");
        Thread.sleep(5000);

        log("DEBUG", "HTTP GET (Group2, 2-й запрос): " + requestUrl);
        HttpGet getRequest2 = new HttpGet(requestUrl);
        getRequest2.setHeader("Authorization", AUTH);
        List<Task> secondBatch = fetchTasks(client, getRequest2);

        if (secondBatch.isEmpty()) {
            log("WARNING", "Group2: во 2-м запросе задач не найдено — считаем, что список нестабилен, ничего не обрабатываем");
            return Collections.emptyList();
        }

        // Пересечение по Task.Id
        Set<Integer> firstIds = firstBatch.stream()
                .map(Task::getId)
                .collect(Collectors.toSet());

        List<Task> stableTasks = secondBatch.stream()
                .filter(task -> firstIds.contains(task.getId()))
                .collect(Collectors.toList());

        int removedCount = firstBatch.size() - stableTasks.size();
        if (removedCount > 0) {
            log("INFO", "Group2: из " + firstBatch.size() + " задач после повторной проверки осталось "
                    + stableTasks.size() + " (убрано " + removedCount + " — вероятно, статус или исполнитель изменились)");
        } else {
            log("INFO", "Group2: все задачи из первого запроса сохранились во втором — список стабилен");
        }

        return stableTasks;
    }

    private static List<Task> fetchTasks(CloseableHttpClient client, HttpGet getRequest) {
        try {
            HttpResponse response = client.execute(getRequest);
            int statusCode = response.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");

            if (statusCode == 200) {
                TaskList tasksWrapper = mapper.readValue(responseBody, TaskList.class);
                return tasksWrapper.getTasks();
            } else {
                log("ERROR", "Ошибка HTTP: код " + statusCode +
                        ", сообщение: " + response.getStatusLine().getReasonPhrase());
                return Collections.emptyList();
            }
        } catch (Exception e) {
            logError("Ошибка получения списка задач", e);
            return Collections.emptyList();
        }
    }

    private static void processTasksInParallel(List<Task> tasks, List<String> activeEmployees, CloseableHttpClient client, boolean isGroup2) {
        ExecutorService executor = Executors.newFixedThreadPool(PARALLELISM);
        List<Future<?>> futures = new ArrayList<>();

        for (Task task : tasks) {
            if (!IS_RUNNING.get()) break;
            Future<?> future = executor.submit(() -> processTask(client, task, activeEmployees, isGroup2));
            futures.add(future);
        }

        for (Future<?> future : futures) {
            try {
                future.get(60, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                logError("Таймаут выполнения задачи", e);
            } catch (Exception e) {
                logError("Ошибка при ожидании завершения задачи", e);
            }
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        printThreadStats();
    }

    private static void waitForNextCheck() {
        try {
            log("DEBUG", "Ожидание " + CHECK_INTERVAL_MS + " мс до следующей проверки...");
            Thread.sleep(CHECK_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("INFO", "Ожидание прервано, продолжаем работу...");
        }
    }

    private static List<String> getActiveEmployees(CloseableHttpClient client, ObjectMapper mapper) {
        try {
            HttpGet getRequest = new HttpGet(ACTIVE_LINE_URL);
            HttpResponse response = client.execute(getRequest);
            int statusCode = response.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");

            if (statusCode == 200) {
                Employee[] employees = mapper.readValue(responseBody, Employee[].class);
                List<String> result = new ArrayList<>();
                for (Employee employee : employees) {
                    String displayName = employee.getDisplayName();
                    if (LOGIN_TO_EXECUTOR_ID.containsKey(displayName)) {
                        result.add(displayName);
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

    private static void clearProcessedTasksCache() {
        synchronized (PROCESSED_TASK_IDS) {
            int sizeBefore = PROCESSED_TASK_IDS.size();
            PROCESSED_TASK_IDS.clear();
            lastCacheClearTime = System.currentTimeMillis();
            log("INFO", "Кэш обработанных задач очищен. Было элементов: " + sizeBefore);
        }
    }

    private static void checkAndArchiveWeeklyStats() {
        long now = System.currentTimeMillis();
        if (now - lastArchiveCheck >= WEEK_IN_MILLIS) {
            try {
                File archiveDir = new File(WEEKLY_ARCHIVE_DIR);
                if (!archiveDir.exists()) {
                    boolean created = archiveDir.mkdirs();
                    if (!created) {
                        log("WARNING", "Не удалось создать директорию архива: " + WEEKLY_ARCHIVE_DIR);
                    }
                }

                String timestamp = LocalDateTime.now(ZoneId.of("Europe/Moscow"))
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
                String archiveFileName = "stats_archive_" + timestamp + ".csv";
                File archiveFile = new File(archiveDir, archiveFileName);

                // Просто копируем текущий файл статистики в архив (если он существует)
                File statsFile = new File(STATS_FILE_PATH);
                if (statsFile.exists()) {
                    Files.copy(statsFile.toPath(), archiveFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    log("INFO", "Еженедельный архив статистики создан: " + archiveFile.getAbsolutePath());
                    // Опционально: можно очистить текущий файл после архивации
                    // Files.write(statsFile.toPath(), "".getBytes(), StandardOpenOption.TRUNCATE_EXISTING);
                } else {
                    log("WARNING", "Файл статистики не найден для архивации: " + STATS_FILE_PATH);
                }
                lastArchiveCheck = now;
            } catch (IOException e) {
                logError("Ошибка при архивации недельной статистики", e);
            }
        }
    }

    private static void initializeStatsFiles() {
        try {
            File statsDir = new File("/app/stats");
            if (!statsDir.exists()) {
                boolean created = statsDir.mkdirs();
                if (!created) {
                    log("WARNING", "Не удалось создать директорию для статистики: /app/stats");
                }
            }

            File statsFile = new File(STATS_FILE_PATH);
            if (!statsFile.exists()) {
                statsFile.createNewFile();
                // Запишем заголовок CSV, если файл новый
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(statsFile))) {
                    bw.write("TaskId,Timestamp,EmployeeLogin,ExecutorId\n");
                }
                log("INFO", "Создан файл статистики: " + STATS_FILE_PATH);
            }

            File summaryFile = new File(SUMMARY_FILE_PATH);
            if (!summaryFile.exists()) {
                summaryFile.createNewFile();
                log("INFO", "Создан файл сводной статистики: " + SUMMARY_FILE_PATH);
            }
        } catch (IOException e) {
            logError("Ошибка инициализации файлов статистики", e);
        }
    }

    private static void saveAssignmentStats() {
        try {
            File file = new File(STATS_FILE_PATH);
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, true))) {
                for (AssignmentStats stats : assignmentHistory) {
                    String line = String.format("%d,%s,%s,%s\n",
                            stats.getTaskId(),
                            stats.getTimestamp(),
                            stats.getEmployeeLogin(),
                            stats.getExecutorId());
                    bw.write(line);
                }
                assignmentHistory.clear(); // Очищаем список после записи
            }
        } catch (IOException e) {
            logError("Ошибка записи статистики в CSV", e);
        }
    }

    private static void saveSummaryStats() {
        try {
            File file = new File(SUMMARY_FILE_PATH);
            StringBuilder sb = new StringBuilder();
            sb.append("=== Сводная статистика ===\n");
            sb.append("Всего обработано задач: ").append(PROCESSED_TASK_IDS.size()).append("\n");

            LocalDate today = LocalDate.now();
            Map<String, Integer> todayStats = dailyStats.get(today);
            if (todayStats != null && !todayStats.isEmpty()) {
                sb.append("Статистика по сотрудникам за сегодня:\n");
                for (Map.Entry<String, Integer> entry : todayStats.entrySet()) {
                    sb.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append(" задач\n");
                }
            } else {
                sb.append("Сегодня задач не назначалось или статистика пуста.\n");
            }

            sb.append("Время последнего обновления: ").append(getCurrentGmt3Time()).append("\n");

            Files.write(file.toPath(), sb.toString().getBytes());
        } catch (IOException e) {
            logError("Ошибка записи сводной статистики", e);
        }
    }

    private static void processTask(CloseableHttpClient client, Task task, List<String> activeEmployees, boolean isGroup2) {
        int taskId = task.getId();
        String executorIds = task.getExecutorIds();

        log("DEBUG", "Поток " + Thread.currentThread().getId() +
                ": обработка задачи №" + taskId +
                ", исполнитель: " + (executorIds != null ? executorIds : "отсутствует") +
                ", группа: " + (isGroup2 ? "Group2 (сервис 47, статус 27)" : "Group1 (обычная)"));

        synchronized (PROCESSED_TASK_IDS) {
            if (PROCESSED_TASK_IDS.contains(taskId)) {
                log("SKIPPED", "Поток " + Thread.currentThread().getId() +
                        ": задача №" + taskId + " уже обработана");
                return;
            }
        }

        // Если у задачи уже есть исполнитель — пропускаем (вне зависимости от группы)
        if (executorIds != null && !executorIds.isEmpty()) {
            log("SKIPPED", "Задача №" + taskId + " уже имеет исполнителя: " + executorIds);
            synchronized (PROCESSED_TASK_IDS) {
                PROCESSED_TASK_IDS.add(taskId);
            }
            return;
        }

        List<String> employeesForAssignment = currentActiveEmployees;
        if (employeesForAssignment.isEmpty()) {
            log("WARNING", "Нет активных сотрудников для назначения задачи №" + taskId);
            return;
        }

        int currentIndex = currentEmployeeIndex.getAndIncrement();
        int employeeIndex = currentIndex % employeesForAssignment.size();
        String selectedEmployeeLogin = employeesForAssignment.get(employeeIndex);
        String targetExecutorId = LOGIN_TO_EXECUTOR_ID.get(selectedEmployeeLogin);

        if (targetExecutorId == null) {
            log("WARNING", "Для сотрудника " + selectedEmployeeLogin + " не найден ExecutorId в конфигурации");
            return;
        }

        log("DEBUG", "Поток " + Thread.currentThread().getId() +
                ": выбран сотрудник " + selectedEmployeeLogin +
                " (ID: " + targetExecutorId + ") для задачи №" + taskId);

        try {
            updateTask(client, taskId, targetExecutorId, isGroup2);

            // --- НАЧАЛО БЛОКА ДОБАВЛЕНИЯ: обновление дневной статистики ---
            LocalDate today = LocalDate.now();
            Map<String, Integer> todayStats = dailyStats.computeIfAbsent(
                    today,
                    date -> new ConcurrentHashMap<>()
            );
            todayStats.merge(selectedEmployeeLogin, 1, Integer::sum);
            // --- КОНЕЦ БЛОКА ДОБАВЛЕНИЯ ---

            synchronized (statsLock) {
                String currentTime = getCurrentGmt3Time();
                AssignmentStats stats = new AssignmentStats(taskId, currentTime, selectedEmployeeLogin, targetExecutorId);
                assignmentHistory.add(stats);
                saveAssignmentStats();
                saveSummaryStats();
            }

            log("SUCCESS", "Поток " + Thread.currentThread().getId() +
                    ": задача №" + taskId +
                    " назначена на сотрудника " + selectedEmployeeLogin + " (ID: " + targetExecutorId);

            synchronized (PROCESSED_TASK_IDS) {
                PROCESSED_TASK_IDS.add(taskId);
            }
        } catch (Exception e) {
            logError("Поток " + Thread.currentThread().getId() +
                    ": ошибка обновления задачи №" + taskId, e);
        }
    }

    private static void updateTask(HttpClient client, int taskId, String executorId, boolean isGroup2) throws IOException {
        log("DEBUG", "Подготовка к обновлению задачи №" + taskId +
                " — назначение исполнителя ID: " + executorId +
                ", режим: " + (isGroup2 ? "Group2 (без смены статуса и комментария)" : "Group1 (полный режим)"));

        try {
            String updateUrl = BASE_URL + "/" + taskId;
            HttpPut putRequest = new HttpPut(updateUrl);
            putRequest.setHeader("Authorization", AUTH);
            putRequest.setHeader("Content-Type", "application/json");

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("ExecutorIds", executorId);
            requestBody.put("ObserverIds", "1598"); // Наблюдатель назначается в обоих случаях

            if (!isGroup2) {
                // Обычная логика: меняем статус и добавляем комментарий
                requestBody.put("StatusId", "27");
                requestBody.put("Comment", "Здравствуйте. Информируем о том, что Ваша заявка в работе.");
                requestBody.put("IsPrivateComment", false);
            }
            // Для Group2: только ExecutorIds и ObserverIds, без статуса и комментария

            String jsonBody = mapper.writeValueAsString(requestBody);
            log("DEBUG", "Тело запроса PUT для задачи №" + taskId + ": " + jsonBody);

            putRequest.setEntity(new StringEntity(jsonBody, "UTF-8"));

            HttpResponse response = client.execute(putRequest);
            int statusCode = response.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");

            log("DEBUG", "Ответ на обновление задачи №" + taskId + ": код " + statusCode);

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
        @JsonProperty("ServiceId")
        private Integer ServiceId; // Добавил поле ServiceId для фильтрации по сервису

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

        public Integer getServiceId() {
            return ServiceId;
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

        public void setServiceId(Integer serviceId) {
            this.ServiceId = serviceId;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskList {
        @JsonProperty("Tasks")
        private List<Task> tasks;
        @JsonProperty("Priorities")
        private List<Object> priorities;

        public List<Task> getTasks() {
            return tasks != null ? tasks : Collections.emptyList();
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
    }

    public static class AssignmentStats {
        private final int taskId;
        private final String timestamp;
        private final String employeeLogin;
        private final String executorId;

        public AssignmentStats(int taskId, String timestamp, String employeeLogin, String executorId) {
            this.taskId = taskId;
            this.timestamp = timestamp;
            this.employeeLogin = employeeLogin;
            this.executorId = executorId;
        }

        public int getTaskId() {
            return taskId;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public String getEmployeeLogin() {
            return employeeLogin;
        }

        public String getExecutorId() {
            return executorId;
        }
    }
}