@echo off
chcp 65001
title J-One Bot
echo.
echo [信息] 正在打包 J-One 机器人工程...
echo.
call mvn clean package -DskipTests=true
echo.
echo [信息] 打包完成，正在启动服务...
echo.
java -Dfile.encoding=utf-8 -Xmx256m -jar target/J-One-0.0.1-SNAPSHOT.jar
pause