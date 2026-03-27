@echo off
echo Running JMeter load test with Docker...

if exist "result.jtl" del "result.jtl"
if exist "html-report" rd /s /q "html-report"

docker run --name jmeter-test --rm -v "%cd%":/jmeter -w /jmeter justb4/jmeter:5.5 -n -t ticket-query-load-test.jmx -l result.jtl -e -o html-report

echo =======================================================
echo Test completed! 
echo HTML report has been generated in the html-report folder.
echo You can open html-report/index.html in your browser.
echo =======================================================
pause
