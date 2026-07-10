import com.kms.katalon.core.annotation.AfterTestCase
import com.kms.katalon.core.annotation.AfterTestSuite
import com.kms.katalon.core.annotation.BeforeTestSuite
import com.kms.katalon.core.context.TestCaseContext
import com.kms.katalon.core.context.TestSuiteContext
import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.logging.KeywordLogger
import java.io.File
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class TestListener {
    private static KeywordLogger log = new KeywordLogger()
    
    private TestSuiteContext testSuiteContext
    private List<TestCaseExecution> testCaseExecutions = []
    private LocalDateTime suiteStartTime
    private LocalDateTime suiteEndTime
    private Map<String, Object> environmentInfo = [:]
    private Map<String, List<String>> screenshotMap = [:]
    private Map<String, List<TestCaseExecution>> moduleGrouping = [:]
    
    private static class TestCaseStep {
        String name
        String status
        long duration
        String message
        
        TestCaseStep(String name, String status, long duration, String message = null) {
            this.name = name
            this.status = status
            this.duration = duration
            this.message = message
        }
    }
    
    private static class TestCaseExecution {
        String name
        String status
        long startTime
        long endTime
        long duration
        String errorMessage
        String module
        List<String> screenshots
        Throwable exception
        List<TestCaseStep> steps
        
        TestCaseExecution(String name) {
            this.name = name
            this.screenshots = []
            this.steps = []
            this.startTime = System.currentTimeMillis()
        }
    }
    
    @BeforeTestSuite
    void beforeSuite(TestSuiteContext testSuiteContext) {
        this.testSuiteContext = testSuiteContext
        this.suiteStartTime = LocalDateTime.now()
        this.testCaseExecutions = []
        this.moduleGrouping = [:]
        collectEnvironmentInfo()
        log.logInfo('TestListener: Suite execution started')
    }
    
    @AfterTestCase
    void afterTestCase(TestCaseContext testCaseContext) {
        String testCaseName = testCaseContext.getTestCaseId()
        String status = testCaseContext.getTestCaseStatus()
        Throwable exception = testCaseContext.getException()
        
        TestCaseExecution execution = new TestCaseExecution(testCaseName)
        execution.status = status
        execution.endTime = System.currentTimeMillis()
        execution.duration = execution.endTime - execution.startTime
        execution.exception = exception
        
        if (exception) {
            execution.errorMessage = exception.getMessage() ?: exception.getClass().getSimpleName()
        }
        
        String module = extractModuleFromTestCase(testCaseName)
        execution.module = module
        
        captureTestSteps(testCaseContext, execution)
        collectScreenshots(testCaseName, execution)
        
        testCaseExecutions.add(execution)
        
        if (!moduleGrouping.containsKey(module)) {
            moduleGrouping[module] = []
        }
        moduleGrouping[module].add(execution)
        
        log.logInfo("TestListener: Test case '${testCaseName}' completed with status: ${status}")
    }
    
    @AfterTestSuite
    void afterSuite(TestSuiteContext testSuiteContext) {
        this.suiteEndTime = LocalDateTime.now()
        generateDashboard()
        log.logInfo('TestListener: Suite execution completed, report generated')
    }
    
    private void captureTestSteps(TestCaseContext testCaseContext, TestCaseExecution execution) {
        try {
            List stepLogs = testCaseContext.getStepNumber() > 0 ? captureStepDetails(testCaseContext) : []
            
            if (stepLogs.size() == 0) {
                execution.steps.add(new TestCaseStep(
                    'Test Execution',
                    execution.status,
                    execution.duration,
                    execution.errorMessage
                ))
            } else {
                execution.steps.addAll(stepLogs)
            }
        } catch (Exception e) {
            log.logWarning("Failed to capture test steps: ${e.message}")
            execution.steps.add(new TestCaseStep('Test Execution', execution.status, execution.duration, execution.errorMessage))
        }
    }
    
    private List<TestCaseStep> captureStepDetails(TestCaseContext testCaseContext) {
        List<TestCaseStep> steps = []
        try {
            int stepCount = testCaseContext.getStepNumber()
            
            for (int i = 1; i <= stepCount; i++) {
                String stepName = "Step ${i}"
                String stepStatus = 'PASSED'
                long stepDuration = 0
                String stepMessage = null
                
                steps.add(new TestCaseStep(stepName, stepStatus, stepDuration, stepMessage))
            }
        } catch (Exception e) {
            log.logWarning("Error capturing step details: ${e.message}")
        }
        
        return steps
    }
    
    private void collectEnvironmentInfo() {
        try {
            environmentInfo.put('Suite Name', testSuiteContext?.getTestSuiteId() ?: 'Unknown')
            environmentInfo.put('Execution Profile', RunConfiguration.getExecutionProfile() ?: 'Default')
            environmentInfo.put('Browser', RunConfiguration.getBrowserType()?.toString() ?: 'N/A')
            environmentInfo.put('Machine Name', InetAddress.getLocalHost().getHostName())
            environmentInfo.put('Operating System', System.getProperty('os.name') + ' ' + System.getProperty('os.version'))
            environmentInfo.put('Java Version', System.getProperty('java.version'))
            environmentInfo.put('Katalon Version', RunConfiguration.getKatalonVersion() ?: 'Unknown')
            environmentInfo.put('Suite Start Time', formatDateTime(suiteStartTime))
        } catch (Exception e) {
            log.logWarning("Failed to collect environment info: ${e.message}")
        }
    }
    
    private String extractModuleFromTestCase(String testCaseName) {
        if (testCaseName.contains('/')) {
            String[] parts = testCaseName.split('/')
            if (parts.length > 1) {
                return parts[parts.length - 2]
            }
        }
        return 'Uncategorized'
    }
    
    private void collectScreenshots(String testCaseName, TestCaseExecution execution) {
        try {
            File reportFolder = new File(RunConfiguration.getReportFolder())
            if (reportFolder.exists()) {
                File screenshotFolder = new File(reportFolder, 'screenshots')
                if (screenshotFolder.exists()) {
                    String testCaseFolder = testCaseName.replaceAll('[^a-zA-Z0-9]', '_')
                    File testFolder = new File(screenshotFolder, testCaseFolder)
                    
                    if (testFolder.exists()) {
                        testFolder.listFiles().each { file ->
                            if (file.name.endsWith('.png') || file.name.endsWith('.jpg')) {
                                execution.screenshots.add(file.getAbsolutePath())
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.logWarning("Failed to collect screenshots: ${e.message}")
        }
    }
    
    private void generateDashboard() {
        try {
            String html = buildCompleteHtml()
            writeReport(html)
            log.logInfo('Dashboard report generated successfully')
        } catch (Exception e) {
            log.logError("Failed to generate dashboard: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private String buildCompleteHtml() {
        StringBuilder html = new StringBuilder()
        html.append('<!DOCTYPE html>\n')
        html.append('<html lang="en">\n')
        html.append('<head>\n')
        html.append(buildHead())
        html.append('</head>\n')
        html.append('<body>\n')
        html.append(buildLayout())
        html.append(buildScripts())
        html.append('</body>\n')
        html.append('</html>\n')
        return html.toString()
    }
    
    private String buildHead() {
        return '''<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Katalon Test Suite Execution Report</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css">
<script src="https://cdnjs.cloudflare.com/ajax/libs/Chart.js/3.9.1/chart.min.js"></script>
<style>
* {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
}

:root {
    --primary-color: #2c3e50;
    --secondary-color: #3498db;
    --success-color: #27ae60;
    --danger-color: #e74c3c;
    --warning-color: #f39c12;
    --info-color: #9b59b6;
    --light-bg: #f8f9fa;
    --card-bg: #ffffff;
    --text-primary: #2c3e50;
    --text-secondary: #7f8c8d;
    --border-color: #ecf0f1;
    --shadow: 0 2px 8px rgba(0,0,0,0.1);
    --shadow-hover: 0 4px 16px rgba(0,0,0,0.15);
}

body {
    font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
    background-color: var(--light-bg);
    color: var(--text-primary);
    line-height: 1.6;
}

.container {
    display: flex;
    min-height: 100vh;
}

.sidebar {
    width: 280px;
    background: var(--primary-color);
    color: white;
    padding: 2rem 0;
    position: fixed;
    height: 100vh;
    overflow-y: auto;
    box-shadow: 2px 0 8px rgba(0,0,0,0.1);
}

.sidebar-logo {
    padding: 0 1.5rem 2rem;
    border-bottom: 1px solid rgba(255,255,255,0.1);
    text-align: center;
    font-size: 1.5rem;
    font-weight: bold;
}

.sidebar-nav {
    list-style: none;
    margin-top: 2rem;
}

.sidebar-nav li {
    margin: 0;
}

.sidebar-nav a {
    display: block;
    padding: 1rem 1.5rem;
    color: rgba(255,255,255,0.8);
    text-decoration: none;
    transition: all 0.3s ease;
    border-left: 3px solid transparent;
    cursor: pointer;
}

.sidebar-nav a:hover,
.sidebar-nav a.active {
    background-color: rgba(52, 152, 219, 0.2);
    color: white;
    border-left-color: var(--secondary-color);
}

.main-content {
    margin-left: 280px;
    padding: 2rem;
    flex: 1;
    width: calc(100% - 280px);
}

.header {
    background: var(--card-bg);
    padding: 2rem;
    border-radius: 12px;
    box-shadow: var(--shadow);
    margin-bottom: 2rem;
    display: flex;
    justify-content: space-between;
    align-items: center;
}

.header-title {
    font-size: 2rem;
    font-weight: 600;
    color: var(--primary-color);
}

.header-info {
    text-align: right;
    font-size: 0.9rem;
    color: var(--text-secondary);
}

.summary-cards {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
    gap: 1.5rem;
    margin-bottom: 2rem;
}

.card {
    background: var(--card-bg);
    padding: 1.5rem;
    border-radius: 12px;
    box-shadow: var(--shadow);
    transition: all 0.3s ease;
    border-top: 4px solid var(--secondary-color);
}

.card:hover {
    box-shadow: var(--shadow-hover);
    transform: translateY(-2px);
}

.card.success {
    border-top-color: var(--success-color);
}

.card.danger {
    border-top-color: var(--danger-color);
}

.card.warning {
    border-top-color: var(--warning-color);
}

.card.info {
    border-top-color: var(--info-color);
}

.card-label {
    font-size: 0.9rem;
    color: var(--text-secondary);
    margin-bottom: 0.5rem;
    text-transform: uppercase;
    letter-spacing: 0.5px;
}

.card-value {
    font-size: 2.5rem;
    font-weight: bold;
    color: var(--text-primary);
}

.card-value.animated {
    animation: countUp 1s ease-out;
}

@keyframes countUp {
    from {
        opacity: 0;
        transform: scale(0.8);
    }
    to {
        opacity: 1;
        transform: scale(1);
    }
}

.card-icon {
    float: right;
    font-size: 2.5rem;
    opacity: 0.1;
}

.charts-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(400px, 1fr));
    gap: 2rem;
    margin-bottom: 2rem;
}

.chart-container {
    background: var(--card-bg);
    padding: 1.5rem;
    border-radius: 12px;
    box-shadow: var(--shadow);
    position: relative;
}

.chart-title {
    font-size: 1.2rem;
    font-weight: 600;
    margin-bottom: 1rem;
    color: var(--primary-color);
}

.chart-wrapper {
    position: relative;
    height: 300px;
    margin-bottom: 1rem;
}

.timeline {
    background: var(--card-bg);
    padding: 2rem;
    border-radius: 12px;
    box-shadow: var(--shadow);
    margin-bottom: 2rem;
}

.timeline-title {
    font-size: 1.5rem;
    font-weight: 600;
    margin-bottom: 1.5rem;
    color: var(--primary-color);
}

.timeline-item {
    display: flex;
    margin-bottom: 2rem;
    padding-bottom: 2rem;
    border-bottom: 1px solid var(--border-color);
}

.timeline-item:last-child {
    border-bottom: none;
}

.timeline-marker {
    width: 40px;
    height: 40px;
    border-radius: 50%;
    background: var(--secondary-color);
    color: white;
    display: flex;
    align-items: center;
    justify-content: center;
    margin-right: 1.5rem;
    flex-shrink: 0;
    font-weight: bold;
}

.timeline-marker.passed {
    background: var(--success-color);
}

.timeline-marker.failed {
    background: var(--danger-color);
}

.timeline-marker.skipped {
    background: var(--warning-color);
}

.timeline-content {
    flex: 1;
}

.timeline-time {
    font-size: 0.9rem;
    color: var(--text-secondary);
    margin-bottom: 0.3rem;
}

.timeline-test-name {
    font-weight: 600;
    color: var(--text-primary);
    margin-bottom: 0.5rem;
}

.timeline-status {
    display: inline-block;
    padding: 0.3rem 0.8rem;
    border-radius: 20px;
    font-size: 0.85rem;
    font-weight: 500;
}

.timeline-status.passed {
    background: rgba(39, 174, 96, 0.2);
    color: var(--success-color);
}

.timeline-status.failed {
    background: rgba(231, 76, 60, 0.2);
    color: var(--danger-color);
}

.timeline-status.skipped {
    background: rgba(243, 156, 18, 0.2);
    color: var(--warning-color);
}

.timeline-duration {
    font-size: 0.9rem;
    color: var(--text-secondary);
}

.table-container {
    background: var(--card-bg);
    border-radius: 12px;
    box-shadow: var(--shadow);
    overflow: hidden;
    margin-bottom: 2rem;
}

.table-header {
    padding: 1.5rem;
    border-bottom: 1px solid var(--border-color);
    display: flex;
    justify-content: space-between;
    align-items: center;
}

.search-box {
    flex: 1;
    max-width: 400px;
}

.search-box input {
    width: 100%;
    padding: 0.5rem 1rem;
    border: 1px solid var(--border-color);
    border-radius: 6px;
    font-size: 0.9rem;
}

.search-box input:focus {
    outline: none;
    border-color: var(--secondary-color);
    box-shadow: 0 0 0 2px rgba(52, 152, 219, 0.1);
}

.table {
    width: 100%;
    border-collapse: collapse;
}

.table thead {
    background: var(--light-bg);
    font-weight: 600;
    color: var(--text-primary);
}

.table th {
    padding: 1rem 1.5rem;
    text-align: left;
    font-size: 0.9rem;
    text-transform: uppercase;
    letter-spacing: 0.5px;
    border-bottom: 1px solid var(--border-color);
}

.table td {
    padding: 1rem 1.5rem;
    border-bottom: 1px solid var(--border-color);
    font-size: 0.9rem;
}

.table tbody tr {
    transition: background-color 0.2s ease;
}

.table tbody tr:hover {
    background-color: var(--light-bg);
}

.status-badge {
    display: inline-block;
    padding: 0.4rem 0.8rem;
    border-radius: 20px;
    font-weight: 500;
    font-size: 0.85rem;
}

.status-badge.passed {
    background: rgba(39, 174, 96, 0.2);
    color: var(--success-color);
}

.status-badge.failed {
    background: rgba(231, 76, 60, 0.2);
    color: var(--danger-color);
}

.status-badge.skipped {
    background: rgba(243, 156, 18, 0.2);
    color: var(--warning-color);
}

.expandable-row {
    cursor: pointer;
}

.expandable-row-main {
    display: flex;
    justify-content: space-between;
    align-items: center;
}

.expandable-row-main .expand-toggle {
    transition: transform 0.3s ease;
}

.expandable-row.expanded .expand-toggle {
    transform: rotate(180deg);
}

.expandable-row-content {
    display: none;
    padding: 1.5rem;
    background: var(--light-bg);
    border-top: 1px solid var(--border-color);
}

.expandable-row.expanded + tr .expandable-row-content {
    display: table-cell;
}

.steps-list {
    margin-top: 1rem;
}

.step-item {
    background: var(--card-bg);
    padding: 1rem;
    margin-bottom: 0.5rem;
    border-radius: 6px;
    border-left: 3px solid var(--secondary-color);
    display: flex;
    justify-content: space-between;
    align-items: center;
}

.step-item.passed {
    border-left-color: var(--success-color);
}

.step-item.failed {
    border-left-color: var(--danger-color);
}

.step-name {
    font-weight: 600;
    color: var(--text-primary);
}

.step-status {
    display: inline-block;
    padding: 0.3rem 0.6rem;
    border-radius: 4px;
    font-size: 0.75rem;
    font-weight: 600;
    text-transform: uppercase;
}

.step-status.passed {
    background: rgba(39, 174, 96, 0.2);
    color: var(--success-color);
}

.step-status.failed {
    background: rgba(231, 76, 60, 0.2);
    color: var(--danger-color);
}

.step-duration {
    font-size: 0.85rem;
    color: var(--text-secondary);
    margin-left: 1rem;
}

.failure-analysis {
    background: var(--card-bg);
    padding: 2rem;
    border-radius: 12px;
    box-shadow: var(--shadow);
    margin-bottom: 2rem;
}

.failure-analysis-title {
    font-size: 1.5rem;
    font-weight: 600;
    margin-bottom: 1.5rem;
    color: var(--primary-color);
}

.failure-categories {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
    gap: 1.5rem;
}

.failure-category {
    background: var(--light-bg);
    padding: 1.5rem;
    border-radius: 8px;
    border-left: 4px solid var(--danger-color);
}

.category-name {
    font-weight: 600;
    color: var(--text-primary);
    margin-bottom: 0.5rem;
}

.category-count {
    font-size: 1.8rem;
    font-weight: bold;
    color: var(--danger-color);
}

.module-summary {
    background: var(--card-bg);
    border-radius: 12px;
    box-shadow: var(--shadow);
    overflow: hidden;
}

.module-summary-table {
    width: 100%;
    border-collapse: collapse;
}

.module-summary-table th {
    background: var(--light-bg);
    padding: 1rem 1.5rem;
    text-align: left;
    font-weight: 600;
    border-bottom: 1px solid var(--border-color);
}

.module-summary-table td {
    padding: 1rem 1.5rem;
    border-bottom: 1px solid var(--border-color);
}

.environment-section {
    background: var(--card-bg);
    padding: 2rem;
    border-radius: 12px;
    box-shadow: var(--shadow);
    margin-bottom: 2rem;
}

.environment-title {
    font-size: 1.5rem;
    font-weight: 600;
    margin-bottom: 1.5rem;
    color: var(--primary-color);
}

.environment-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
    gap: 1.5rem;
}

.environment-item {
    background: var(--light-bg);
    padding: 1rem;
    border-radius: 8px;
}

.environment-label {
    font-size: 0.85rem;
    color: var(--text-secondary);
    text-transform: uppercase;
    letter-spacing: 0.5px;
    margin-bottom: 0.3rem;
}

.environment-value {
    font-weight: 600;
    color: var(--text-primary);
    word-break: break-word;
}

.footer {
    background: var(--primary-color);
    color: white;
    text-align: center;
    padding: 2rem;
    margin-top: 3rem;
}

.footer-text {
    font-size: 0.9rem;
    opacity: 0.8;
}

.hidden {
    display: none !important;
}

.section {
    display: none;
}

.section.active {
    display: block;
}

.module-section {
    margin-bottom: 2rem;
    background: var(--card-bg);
    border-radius: 12px;
    box-shadow: var(--shadow);
    overflow: hidden;
}

.module-section-header {
    background: var(--light-bg);
    padding: 1.5rem;
    border-bottom: 2px solid var(--border-color);
    cursor: pointer;
}

.module-section-header:hover {
    background: var(--border-color);
}

.module-section-title {
    font-weight: 600;
    color: var(--primary-color);
    display: flex;
    justify-content: space-between;
    align-items: center;
}

.module-stats {
    display: flex;
    gap: 2rem;
    margin-top: 0.5rem;
    font-size: 0.9rem;
}

.module-stat {
    display: flex;
    align-items: center;
    gap: 0.5rem;
}

.module-stat-value {
    font-weight: 600;
}

.module-section-content {
    padding: 1.5rem;
    max-height: 1000px;
    overflow: hidden;
    transition: all 0.3s ease;
}

.module-section.collapsed .module-section-content {
    max-height: 0;
    padding: 0 1.5rem;
}

.screenshot-container {
    margin-top: 1rem;
    padding-top: 1rem;
    border-top: 1px solid var(--border-color);
}

.screenshot-label {
    font-weight: 600;
    color: var(--text-primary);
    margin-bottom: 0.5rem;
}

.screenshot-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 1rem;
}

.screenshot-thumbnail {
    width: 100%;
    height: 100px;
    object-fit: cover;
    border-radius: 6px;
    cursor: pointer;
    transition: all 0.2s ease;
    border: 1px solid var(--border-color);
}

.screenshot-thumbnail:hover {
    transform: scale(1.05);
    box-shadow: var(--shadow-hover);
}

.modal {
    display: none;
    position: fixed;
    z-index: 1000;
    left: 0;
    top: 0;
    width: 100%;
    height: 100%;
    background-color: rgba(0,0,0,0.7);
    animation: fadeIn 0.3s ease;
}

.modal.show {
    display: block;
}

@keyframes fadeIn {
    from { opacity: 0; }
    to { opacity: 1; }
}

.modal-content {
    background-color: white;
    margin: auto;
    padding: 2rem;
    border-radius: 12px;
    width: 90%;
    max-width: 90vh;
    max-height: 90vh;
    overflow: auto;
    position: absolute;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
}

.close-modal {
    color: var(--text-secondary);
    float: right;
    font-size: 2rem;
    font-weight: bold;
    cursor: pointer;
    transition: color 0.2s ease;
}

.close-modal:hover {
    color: var(--danger-color);
}

.modal-image {
    width: 100%;
    max-height: 80vh;
    object-fit: contain;
}

@media (max-width: 768px) {
    .sidebar {
        width: 100%;
        height: auto;
        position: relative;
    }
    
    .main-content {
        margin-left: 0;
        width: 100%;
    }
    
    .header {
        flex-direction: column;
        gap: 1rem;
        text-align: left;
    }
    
    .header-info {
        text-align: left;
    }
    
    .summary-cards {
        grid-template-columns: 1fr;
    }
    
    .charts-grid {
        grid-template-columns: 1fr;
    }
    
    .chart-wrapper {
        height: 250px;
    }
    
    .table {
        font-size: 0.8rem;
    }
    
    .table th,
    .table td {
        padding: 0.5rem;
    }
}
</style>
'''
    }
    
    private String buildLayout() {
        return '''<div class="container">
    <div class="sidebar">
        <div class="sidebar-logo">
            <i class="fas fa-chart-line"></i> Test Report
        </div>
        <ul class="sidebar-nav">
            <li><a class="nav-link active" onclick="showSection('dashboard')"><i class="fas fa-tachometer-alt"></i> Dashboard</a></li>
            <li><a class="nav-link" onclick="showSection('summary')"><i class="fas fa-list"></i> Summary</a></li>
            <li><a class="nav-link" onclick="showSection('timeline')"><i class="fas fa-clock"></i> Timeline</a></li>
            <li><a class="nav-link" onclick="showSection('charts')"><i class="fas fa-chart-bar"></i> Charts</a></li>
            <li><a class="nav-link" onclick="showSection('execution-log')"><i class="fas fa-list-ul"></i> Execution Log</a></li>
            <li><a class="nav-link" onclick="showSection('failure-analysis')"><i class="fas fa-exclamation-triangle"></i> Failures</a></li>
            <li><a class="nav-link" onclick="showSection('environment')"><i class="fas fa-info-circle"></i> Environment</a></li>
        </ul>
    </div>
    
    <div class="main-content">
        ''' + buildHeader() + '''
        
        <div id="dashboard" class="section active">
            ''' + buildSummaryCards() + '''
            ''' + buildCharts() + '''
        </div>
        
        <div id="summary" class="section">
            ''' + buildModuleSummary() + '''
        </div>
        
        <div id="timeline" class="section">
            ''' + buildExecutionTimeline() + '''
        </div>
        
        <div id="charts" class="section">
            ''' + buildCharts() + '''
        </div>
        
        <div id="execution-log" class="section">
            ''' + buildExecutionTable() + '''
        </div>
        
        <div id="failure-analysis" class="section">
            ''' + buildFailureAnalysis() + '''
        </div>
        
        <div id="environment" class="section">
            ''' + buildEnvironmentSection() + '''
        </div>
    </div>
</div>

<div id="screenshotModal" class="modal">
    <div class="modal-content">
        <span class="close-modal" onclick="closeScreenshotModal()">&times;</span>
        <img id="screenshotImage" class="modal-image" src="" alt="Screenshot">
    </div>
</div>

''' + buildFooter() + '''
'''
    }
    
    private String buildHeader() {
        long totalDuration = suiteEndTime.toLocalTime().toNanoOfDay() - suiteStartTime.toLocalTime().toNanoOfDay()
        totalDuration = totalDuration < 0 ? 0 : TimeUnit.NANOSECONDS.toSeconds(totalDuration)
        
        return """<div class="header">
    <div>
        <div class="header-title"><i class="fas fa-file-alt"></i> Test Suite Execution Report</div>
        <div class="header-info">
            <p><strong>Suite:</strong> ${environmentInfo.get('Suite Name') ?: 'N/A'}</p>
        </div>
    </div>
    <div class="header-info">
        <p><strong>Execution Time:</strong> ${formatDateTime(suiteStartTime)}</p>
        <p><strong>Duration:</strong> ${totalDuration} seconds</p>
    </div>
</div>
"""
    }
    
    private String buildSummaryCards() {
        int totalTests = testCaseExecutions.size()
        int passedTests = testCaseExecutions.count { it.status == 'PASSED' }
        int failedTests = testCaseExecutions.count { it.status == 'FAILED' }
        int skippedTests = testCaseExecutions.count { it.status == 'SKIPPED' }
        int errorTests = testCaseExecutions.count { it.status == 'ERROR' }
        double passPercentage = totalTests > 0 ? (passedTests / totalTests * 100).toDouble() : 0
        double failPercentage = totalTests > 0 ? (failedTests / totalTests * 100).toDouble() : 0
        long totalDuration = calculateTotalDuration()
        long avgDuration = totalTests > 0 ? totalDuration / totalTests : 0
        
        return """<div class="summary-cards">
    <div class="card success">
        <div class="card-label"><i class="fas fa-check-circle"></i> Total Tests</div>
        <div class="card-value animated">${totalTests}</div>
        <div class="card-icon"><i class="fas fa-vial"></i></div>
    </div>
    <div class="card success">
        <div class="card-label"><i class="fas fa-check"></i> Passed</div>
        <div class="card-value animated" style="color: var(--success-color);">${passedTests}</div>
        <div class="card-icon"><i class="fas fa-thumbs-up"></i></div>
    </div>
    <div class="card danger">
        <div class="card-label"><i class="fas fa-times"></i> Failed</div>
        <div class="card-value animated" style="color: var(--danger-color);">${failedTests}</div>
        <div class="card-icon"><i class="fas fa-times-circle"></i></div>
    </div>
    <div class="card warning">
        <div class="card-label"><i class="fas fa-forward"></i> Skipped</div>
        <div class="card-value animated" style="color: var(--warning-color);">${skippedTests}</div>
        <div class="card-icon"><i class="fas fa-forward-fast"></i></div>
    </div>
    <div class="card info">
        <div class="card-label"><i class="fas fa-exclamation"></i> Errors</div>
        <div class="card-value animated" style="color: var(--info-color);">${errorTests}</div>
        <div class="card-icon"><i class="fas fa-bug"></i></div>
    </div>
    <div class="card success">
        <div class="card-label"><i class="fas fa-percentage"></i> Pass Rate</div>
        <div class="card-value animated" style="color: var(--success-color);">${String.format('%.1f', passPercentage)}%</div>
        <div class="card-icon"><i class="fas fa-chart-pie"></i></div>
    </div>
    <div class="card danger">
        <div class="card-label"><i class="fas fa-percentage"></i> Fail Rate</div>
        <div class="card-value animated" style="color: var(--danger-color);">${String.format('%.1f', failPercentage)}%</div>
        <div class="card-icon"><i class="fas fa-chart-pie"></i></div>
    </div>
    <div class="card info">
        <div class="card-label"><i class="fas fa-hourglass"></i> Duration</div>
        <div class="card-value animated" style="color: var(--info-color);">${formatDuration(totalDuration)}</div>
        <div class="card-icon"><i class="fas fa-clock"></i></div>
    </div>
</div>
"""
    }
    
    private String buildCharts() {
        return """<div class="charts-grid">
    <div class="chart-container">
        <div class="chart-title"><i class="fas fa-chart-doughnut"></i> Pass vs Fail</div>
        <div class="chart-wrapper">
            <canvas id="passFailChart"></canvas>
        </div>
    </div>
    <div class="chart-container">
        <div class="chart-title"><i class="fas fa-chart-bar"></i> Module Summary</div>
        <div class="chart-wrapper">
            <canvas id="moduleSummaryChart"></canvas>
        </div>
    </div>
    <div class="chart-container">
        <div class="chart-title"><i class="fas fa-chart-pie"></i> Failure Categories</div>
        <div class="chart-wrapper">
            <canvas id="failureCategoriesChart"></canvas>
        </div>
    </div>
    <div class="chart-container">
        <div class="chart-title"><i class="fas fa-chart-line"></i> Execution Timeline</div>
        <div class="chart-wrapper">
            <canvas id="executionTimelineChart"></canvas>
        </div>
    </div>
</div>
"""
    }
    
    private String buildExecutionTimeline() {
        StringBuilder html = new StringBuilder()
        html.append('<div class="timeline">\n')
        html.append('<div class="timeline-title"><i class="fas fa-clock"></i> Test Execution Timeline</div>\n')
        
        testCaseExecutions.each { execution ->
            String statusClass = execution.status.toLowerCase()
            String statusIcon = execution.status == 'PASSED' ? 'fa-check' : 
                               execution.status == 'FAILED' ? 'fa-times' : 'fa-forward'
            
            html.append("""<div class="timeline-item">
    <div class="timeline-marker ${statusClass}">
        <i class="fas ${statusIcon}"></i>
    </div>
    <div class="timeline-content">
        <div class="timeline-time">${formatDurationMs(execution.duration)}</div>
        <div class="timeline-test-name">${escapeHtml(execution.name)}</div>
        <span class="timeline-status ${statusClass}">${execution.status}</span>
        ${execution.errorMessage ? '<div class="timeline-duration"><strong>Error:</strong> ' + escapeHtml(execution.errorMessage) + '</div>' : '<div class="timeline-duration"><strong>Module:</strong> ' + escapeHtml(execution.module) + '</div>'}
    </div>
</div>
""")
        }
        
        html.append('</div>\n')
        return html.toString()
    }
    
    private String buildModuleSummary() {
        StringBuilder html = new StringBuilder()
        html.append('<div class="module-summary">\n')
        html.append('<table class="module-summary-table">\n')
        html.append('<thead><tr>\n')
        html.append('<th>Module</th>\n')
        html.append('<th>Total</th>\n')
        html.append('<th>Passed</th>\n')
        html.append('<th>Failed</th>\n')
        html.append('<th>Pass %</th>\n')
        html.append('</tr></thead>\n')
        html.append('<tbody>\n')
        
        moduleGrouping.each { module, executions ->
            int total = executions.size()
            int passed = executions.count { it.status == 'PASSED' }
            int failed = executions.count { it.status == 'FAILED' }
            double passPercent = total > 0 ? (passed / total * 100).toDouble() : 0
            
            html.append("""<tr>
    <td><strong>${escapeHtml(module)}</strong></td>
    <td>${total}</td>
    <td><span class="status-badge passed">${passed}</span></td>
    <td><span class="status-badge failed">${failed}</span></td>
    <td>${String.format('%.1f', passPercent)}%</td>
</tr>
""")
        }
        
        html.append('</tbody>\n')
        html.append('</table>\n')
        html.append('</div>\n')
        
        return html.toString()
    }
    
    private String buildExecutionTable() {
        StringBuilder html = new StringBuilder()
        html.append('<div class="table-container">\n')
        html.append('''<div class="table-header">
    <div class="search-box">
        <input type="text" id="searchInput" placeholder="Search test cases..." onkeyup="filterTable()">
    </div>
</div>
''')
        html.append('<table class="table" id="executionTable">\n')
        html.append('''<thead>
<tr>
    <th>Test Case</th>
    <th>Module</th>
    <th>Status</th>
    <th>Duration</th>
    <th style="width: 50px;"></th>
</tr>
</thead>
<tbody>
''')
        
        testCaseExecutions.eachWithIndex { execution, index ->
            String statusClass = execution.status.toLowerCase()
            String rowId = "row-${index}"
            String contentId = "content-${index}"
            
            html.append("""<tr class="expandable-row" id="${rowId}">
    <td onclick="toggleRow('${rowId}')"><strong>${escapeHtml(execution.name)}</strong></td>
    <td onclick="toggleRow('${rowId}')">${escapeHtml(execution.module)}</td>
    <td onclick="toggleRow('${rowId}')"><span class="status-badge ${statusClass}">${execution.status}</span></td>
    <td onclick="toggleRow('${rowId}')">${formatDurationMs(execution.duration)}</td>
    <td onclick="toggleRow('${rowId}')" style="text-align: center;"><i class="fas fa-chevron-down expand-toggle"></i></td>
</tr>
<tr id="${contentId}" style="display: none;">
    <td colspan="5">
        <div class="expandable-row-content">
            <div><strong>Test Case:</strong> ${escapeHtml(execution.name)}</div>
            <div><strong>Module:</strong> ${escapeHtml(execution.module)}</div>
            <div><strong>Status:</strong> <span class="status-badge ${statusClass}">${execution.status}</span></div>
            <div><strong>Duration:</strong> ${formatDurationMs(execution.duration)}</div>
            ${execution.errorMessage ? '<div><strong>Error Message:</strong> ' + escapeHtml(execution.errorMessage) + '</div>' : ''}
            ${buildStepsSection(execution)}
            ${execution.screenshots.size() > 0 ? buildScreenshotGrid(execution.screenshots) : ''}
        </div>
    </td>
</tr>
""")
        }
        
        html.append('</tbody>\n')
        html.append('</table>\n')
        html.append('</div>\n')
        
        return html.toString()
    }
    
    private String buildStepsSection(TestCaseExecution execution) {
        if (!execution.steps || execution.steps.isEmpty()) {
            return ''
        }
        
        StringBuilder html = new StringBuilder()
        html.append('<div class="steps-list" style="margin-top: 1rem;">\n')
        html.append('<strong style="display: block; margin-bottom: 0.5rem;"><i class="fas fa-tasks"></i> Test Steps:</strong>\n')
        
        execution.steps.eachWithIndex { step, index ->
            String stepStatusClass = step.status.toLowerCase()
            html.append("""<div class="step-item ${stepStatusClass}">
    <div>
        <div class="step-name">#${index + 1} ${escapeHtml(step.name)}</div>
        ${step.message ? '<div style="font-size: 0.85rem; color: var(--text-secondary); margin-top: 0.3rem;">' + escapeHtml(step.message) + '</div>' : ''}
    </div>
    <div style="display: flex; align-items: center;">
        <span class="step-status ${stepStatusClass}">${step.status}</span>
        ${step.duration > 0 ? '<span class="step-duration">' + formatDurationMs(step.duration) + '</span>' : ''}
    </div>
</div>
""")
        }
        
        html.append('</div>\n')
        return html.toString()
    }
    
    private String buildScreenshotGrid(List<String> screenshots) {
        StringBuilder html = new StringBuilder()
        html.append('<div class="screenshot-container">\n')
        html.append('<div class="screenshot-label"><i class="fas fa-image"></i> Screenshots</div>\n')
        html.append('<div class="screenshot-grid">\n')
        
        screenshots.each { screenshotPath ->
            String encodedPath = screenshotPath.replace('\\', '/')
            html.append("""<img src="file:///${encodedPath}" class="screenshot-thumbnail" onclick="showScreenshot('file:///${encodedPath}')" alt="Screenshot">
""")
        }
        
        html.append('</div>\n')
        html.append('</div>\n')
        
        return html.toString()
    }
    
    private String buildFailureAnalysis() {
        StringBuilder html = new StringBuilder()
        html.append('<div class="failure-analysis">\n')
        html.append('<div class="failure-analysis-title"><i class="fas fa-exclamation-triangle"></i> Failure Analysis</div>\n')
        
        Map<String, Integer> failureCategories = categorizeFailures()
        
        html.append('<div class="failure-categories">\n')
        
        if (failureCategories.isEmpty()) {
            html.append('<div style="grid-column: 1 / -1; text-align: center; padding: 2rem; color: var(--text-secondary);"><i class="fas fa-check-circle"></i> No failures detected!</div>\n')
        } else {
            failureCategories.each { category, count ->
                html.append("""<div class="failure-category">
    <div class="category-name">${escapeHtml(category)}</div>
    <div class="category-count">${count}</div>
</div>
""")
            }
        }
        
        html.append('</div>\n')
        html.append('</div>\n')
        
        return html.toString()
    }
    
    private String buildEnvironmentSection() {
        StringBuilder html = new StringBuilder()
        html.append('<div class="environment-section">\n')
        html.append('<div class="environment-title"><i class="fas fa-server"></i> Environment Information</div>\n')
        html.append('<div class="environment-grid">\n')
        
        environmentInfo.each { key, value ->
            html.append("""<div class="environment-item">
    <div class="environment-label">${escapeHtml(key)}</div>
    <div class="environment-value">${escapeHtml(value?.toString() ?: 'N/A')}</div>
</div>
""")
        }
        
        html.append('</div>\n')
        html.append('</div>\n')
        
        return html.toString()
    }
    
    private String buildFooter() {
        LocalDateTime now = LocalDateTime.now()
        return """<div class="footer">
    <div class="footer-text">
        <p><i class="fas fa-file-alt"></i> Katalon Studio Test Execution Report</p>
        <p>Generated on ${formatDateTime(now)} | <i class="fas fa-check-circle"></i> Report Generated Successfully</p>
    </div>
</div>
"""
    }
    
    private String buildScripts() {
        return """<script>
let passFailChart, moduleSummaryChart, failureCategoriesChart, executionTimelineChart;

document.addEventListener('DOMContentLoaded', function() {
    initializeCharts();
    animateCounters();
});

function showSection(sectionId) {
    document.querySelectorAll('.section').forEach(section => {
        section.classList.remove('active');
    });
    document.querySelectorAll('.nav-link').forEach(link => {
        link.classList.remove('active');
    });
    
    const section = document.getElementById(sectionId);
    if (section) {
        section.classList.add('active');
        event.target.closest('.nav-link').classList.add('active');
        
        if (sectionId === 'charts' && passFailChart === null) {
            setTimeout(initializeCharts, 100);
        }
    }
}

function initializeCharts() {
    const testData = ${getTestData()};
    const moduleData = ${getModuleData()};
    const failureData = ${getFailureData()};
    const timelineData = ${getTimelineData()};
    
    const chartConfig = {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
            legend: {
                labels: { font: { size: 12, family: "'Segoe UI', Tahoma, Geneva, Verdana, sans-serif" } }
            }
        }
    };
    
    // Pass/Fail Chart
    const passFailCtx = document.getElementById('passFailChart');
    if (passFailCtx) {
        if (passFailChart) passFailChart.destroy();
        passFailChart = new Chart(passFailCtx, {
            type: 'doughnut',
            data: {
                labels: ['Passed', 'Failed', 'Skipped', 'Errors'],
                datasets: [{
                    data: testData,
                    backgroundColor: ['#27ae60', '#e74c3c', '#f39c12', '#9b59b6'],
                    borderColor: '#fff',
                    borderWidth: 2
                }]
            },
            options: chartConfig
        });
    }
    
    // Module Summary Chart
    const moduleSummaryCtx = document.getElementById('moduleSummaryChart');
    if (moduleSummaryCtx) {
        if (moduleSummaryChart) moduleSummaryChart.destroy();
        moduleSummaryChart = new Chart(moduleSummaryCtx, {
            type: 'bar',
            data: {
                labels: moduleData.labels,
                datasets: [
                    {
                        label: 'Passed',
                        data: moduleData.passed,
                        backgroundColor: '#27ae60'
                    },
                    {
                        label: 'Failed',
                        data: moduleData.failed,
                        backgroundColor: '#e74c3c'
                    }
                ]
            },
            options: {
                ...chartConfig,
                scales: {
                    y: { beginAtZero: true }
                }
            }
        });
    }
    
    // Failure Categories Chart
    const failureCategoriesCtx = document.getElementById('failureCategoriesChart');
    if (failureCategoriesCtx) {
        if (failureCategoriesChart) failureCategoriesChart.destroy();
        failureCategoriesChart = new Chart(failureCategoriesCtx, {
            type: 'pie',
            data: {
                labels: failureData.labels,
                datasets: [{
                    data: failureData.values,
                    backgroundColor: ['#e74c3c', '#e67e22', '#f39c12', '#3498db', '#9b59b6', '#95a5a6'],
                    borderColor: '#fff',
                    borderWidth: 2
                }]
            },
            options: chartConfig
        });
    }
    
    // Timeline Chart
    const executionTimelineCtx = document.getElementById('executionTimelineChart');
    if (executionTimelineCtx) {
        if (executionTimelineChart) executionTimelineChart.destroy();
        executionTimelineChart = new Chart(executionTimelineCtx, {
            type: 'line',
            data: {
                labels: timelineData.labels,
                datasets: [{
                    label: 'Test Duration (ms)',
                    data: timelineData.data,
                    borderColor: '#3498db',
                    backgroundColor: 'rgba(52, 152, 219, 0.1)',
                    tension: 0.4,
                    fill: true,
                    borderWidth: 2
                }]
            },
            options: {
                ...chartConfig,
                scales: {
                    y: { beginAtZero: true }
                }
            }
        });
    }
}

function animateCounters() {
    document.querySelectorAll('.card-value.animated').forEach(element => {
        const finalValue = parseInt(element.textContent);
        let currentValue = 0;
        const increment = Math.ceil(finalValue / 30);
        
        const interval = setInterval(() => {
            currentValue += increment;
            if (currentValue >= finalValue) {
                element.textContent = finalValue;
                clearInterval(interval);
            } else {
                element.textContent = currentValue;
            }
        }, 30);
    });
}

function filterTable() {
    const input = document.getElementById('searchInput');
    const filter = input.value.toLowerCase();
    const table = document.getElementById('executionTable');
    const rows = table.getElementsByTagName('tr');
    
    for (let i = 1; i < rows.length; i++) {
        const row = rows[i];
        const text = row.textContent || row.innerText;
        row.style.display = text.toLowerCase().includes(filter) ? '' : 'none';
    }
}

function toggleRow(rowId) {
    const row = document.getElementById(rowId);
    const contentId = rowId.replace('row-', 'content-');
    const contentRow = document.getElementById(contentId);
    
    if (contentRow.style.display === 'none') {
        contentRow.style.display = 'table-row';
        row.classList.add('expanded');
    } else {
        contentRow.style.display = 'none';
        row.classList.remove('expanded');
    }
}

function showScreenshot(imagePath) {
    const modal = document.getElementById('screenshotModal');
    const image = document.getElementById('screenshotImage');
    image.src = imagePath;
    modal.classList.add('show');
}

function closeScreenshotModal() {
    const modal = document.getElementById('screenshotModal');
    modal.classList.remove('show');
}

window.onclick = function(event) {
    const modal = document.getElementById('screenshotModal');
    if (event.target == modal) {
        modal.classList.remove('show');
    }
};
</script>
"""
    }
    
    private String getTestData() {
        int passed = testCaseExecutions.count { it.status == 'PASSED' }
        int failed = testCaseExecutions.count { it.status == 'FAILED' }
        int skipped = testCaseExecutions.count { it.status == 'SKIPPED' }
        int errors = testCaseExecutions.count { it.status == 'ERROR' }
        
        return "[${passed}, ${failed}, ${skipped}, ${errors}]"
    }
    
    private String getModuleData() {
        StringBuilder json = new StringBuilder()
        json.append('{"labels": [')
        
        List<String> modules = moduleGrouping.keySet().toList()
        modules.eachWithIndex { module, index ->
            json.append("\"${escapeJson(module)}\"")
            if (index < modules.size() - 1) json.append(', ')
        }
        
        json.append('], "passed": [')
        modules.eachWithIndex { module, index ->
            int passedCount = moduleGrouping[module].count { it.status == 'PASSED' }
            json.append(passedCount)
            if (index < modules.size() - 1) json.append(', ')
        }
        
        json.append('], "failed": [')
        modules.eachWithIndex { module, index ->
            int failedCount = moduleGrouping[module].count { it.status == 'FAILED' }
            json.append(failedCount)
            if (index < modules.size() - 1) json.append(', ')
        }
        
        json.append(']}')
        return json.toString()
    }
    
    private String getFailureData() {
        Map<String, Integer> failureCategories = categorizeFailures()
        
        StringBuilder json = new StringBuilder()
        json.append('{"labels": [')
        
        List<String> categories = failureCategories.keySet().toList()
        categories.eachWithIndex { category, index ->
            json.append("\"${escapeJson(category)}\"")
            if (index < categories.size() - 1) json.append(', ')
        }
        
        json.append('], "values": [')
        categories.eachWithIndex { category, index ->
            json.append(failureCategories[category])
            if (index < categories.size() - 1) json.append(', ')
        }
        
        json.append(']}')
        return json.toString()
    }
    
    private String getTimelineData() {
        StringBuilder json = new StringBuilder()
        json.append('{"labels": [')
        
        testCaseExecutions.eachWithIndex { execution, index ->
            json.append("\"Test ${index + 1}\"")
            if (index < testCaseExecutions.size() - 1) json.append(', ')
        }
        
        json.append('], "data": [')
        testCaseExecutions.eachWithIndex { execution, index ->
            json.append(execution.duration)
            if (index < testCaseExecutions.size() - 1) json.append(', ')
        }
        
        json.append(']}')
        return json.toString()
    }
    
    private Map<String, Integer> categorizeFailures() {
        Map<String, Integer> categories = [:]
        
        testCaseExecutions.findAll { it.status == 'FAILED' || it.status == 'ERROR' }.each { execution ->
            if (execution.exception) {
                String exceptionClass = execution.exception.getClass().getSimpleName()
                categories[exceptionClass] = (categories[exceptionClass] ?: 0) + 1
            } else if (execution.errorMessage) {
                String errorType = 'Other'
                if (execution.errorMessage.contains('AssertionError')) {
                    errorType = 'AssertionError'
                } else if (execution.errorMessage.contains('TimeoutException')) {
                    errorType = 'TimeoutException'
                } else if (execution.errorMessage.contains('NoSuchElementException')) {
                    errorType = 'NoSuchElementException'
                } else if (execution.errorMessage.contains('SocketTimeoutException')) {
                    errorType = 'SocketTimeoutException'
                }
                categories[errorType] = (categories[errorType] ?: 0) + 1
            }
        }
        
        return categories
    }
    
    private void writeReport(String html) {
        try {
            File reportFolder = new File(RunConfiguration.getReportFolder())
            if (!reportFolder.exists()) {
                reportFolder.mkdirs()
            }
            
            File reportFile = new File(reportFolder, 'UniversalTestSuiteExecutionReport.html')
            PrintWriter writer = new PrintWriter(reportFile, 'UTF-8')
            
            try {
                writer.print(html)
                writer.flush()
                log.logInfo("Report written to: ${reportFile.getAbsolutePath()}")
            } finally {
                writer.close()
            }
        } catch (Exception e) {
            log.logError("Error writing report: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private long calculateTotalDuration() {
        return testCaseExecutions.sum { it.duration } as Long
    }
    
    private String formatDateTime(LocalDateTime dateTime) {
        if (!dateTime) return 'N/A'
        return dateTime.format(DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm:ss'))
    }
    
    private String formatDuration(long seconds) {
        if (seconds < 60) return "${seconds}s"
        long minutes = seconds / 60
        long remainingSeconds = seconds % 60
        return "${minutes}m ${remainingSeconds}s"
    }
    
    private String formatDurationMs(long milliseconds) {
        long seconds = milliseconds / 1000
        long ms = milliseconds % 1000
        
        if (milliseconds < 1000) return "${milliseconds}ms"
        if (milliseconds < 60000) return "${seconds}s ${ms}ms"
        
        long minutes = seconds / 60
        long remainingSeconds = seconds % 60
        return "${minutes}m ${remainingSeconds}s"
    }
    
    private String escapeHtml(String text) {
        if (!text) return ''
        return text
            .replace('&', '&amp;')
            .replace('<', '&lt;')
            .replace('>', '&gt;')
            .replace('"', '&quot;')
            .replace("'", '&#39;')
    }
    
    private String escapeJson(String text) {
        if (!text) return ''
        return text
            .replace('\\', '\\\\')
            .replace('"', '\\"')
            .replace('\n', '\\n')
            .replace('\r', '\\r')
            .replace('\t', '\\t')
    }
}
