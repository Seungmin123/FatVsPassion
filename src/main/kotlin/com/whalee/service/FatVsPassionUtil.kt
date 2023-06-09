package com.whalee.service

import com.whalee.dto.UserInfo
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import java.sql.DriverManager
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// TODO 개인 목표 횟수 테이블 & API 개발
// TODO toDate & endDate 를 통해 해당 주 몇 회 실시하였는지, 몇 주차엔 달성하였꼬 실패하였는지 API
class FatVsPassionUtil(private val token: String, private val chatName: String) : TelegramLongPollingBot() {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val dbConnection = DriverManager.getConnection("jdbc:sqlite:mentions.db")

    init {
        dbConnection.createStatement().execute(
            "CREATE TABLE IF NOT EXISTS mentions (" +
                    "mention_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "user_id BIGINT, " +
                    "user_name TEXT, " +
                    "date TEXT," +
                    "chat_id BIGINT)"
        )
    }

    override fun getBotUsername(): String = chatName

    override fun getBotToken(): String = token

    override fun onUpdateReceived(update: Update) {
        if (update.hasMessage() && update.message.hasText()) {
            val message = update.message
            val userName: String? = "${message.from.lastName ?: ""} ${message.from.firstName ?: ""}"
            val userInfo = UserInfo(message.from.id, userName, LocalDate.now(), message.chat.id)

            if(message.text.startsWith("/start")){
                selectIndexMessage(userInfo.chatId)

            }else if(message.text.startsWith("/명령어")){
                selectMethodList(userInfo.chatId)

            }else if(message.text.startsWith("/오운완")){
                exerciseInsert(userInfo)

            }else if(message.text.startsWith("/주간집계")){
                var dateTime: LocalDate? = null
                if(message.text.matches(Regex("^/주간집계\\s\\d{4}-\\d{2}-\\d{2}"))){
                    dateTime = LocalDate.parse(message.text.substringAfter(" "))
                }
                selectWeeklyCntMsg(userInfo, dateTime)

            }else if(message.text.startsWith("/삭제")){
                deleteUserRecentData(userInfo)

            }else if(message.text.startsWith("/크크루삥뽕")){
                deleteAllData(userInfo.chatId)

            }
        }
    }

    fun sendTextMessage(chatId: Long, text: String) {
        // 메시지 전송
        val message = SendMessage(chatId.toString(), text)
        try {
            execute<Message, SendMessage>(message)
        } catch (e: TelegramApiException) {}
    }

    fun selectIndexMessage(chatId: Long){
        val message = "안녕하세요! 지방은 열정을 이길 수 없다 Ver_1.3 입니다.\n" +
                "/명령어: 명령어 모음"
        sendTextMessage(chatId, message)
    }

    fun selectMethodList(chatId: Long) {
        val message = "명령어 모음 입니다.\n" +
                "/오운완: 개인별 운동 기록\n" +
                "/주간집계: 일주일간 총 집계\n" +
                "/주간집계 yyyy-mm-dd: 해당 일자가 포함되어있는 일주일간 총 집계\n" +
                "/삭제: 최근 입력 데이터 삭제"
        sendTextMessage(chatId, message)
    }

    fun exerciseInsert(userInfo: UserInfo) {
            val insertQuery = "INSERT INTO mentions(user_id, user_name, date, chat_id) VALUES (?, ?, ?, ?)"
            val statement = dbConnection.prepareStatement(insertQuery)
            statement.setLong(1, userInfo.userId)
            statement.setString(2, userInfo.userName)
            statement.setString(3, userInfo.date.format(dateFormatter))
            statement.setLong(4, userInfo.chatId)

            statement.executeUpdate()

            statement.close()

            val message = "${userInfo.userName ?: ""} 님! 입력완료입니다!"

        sendTextMessage(userInfo.chatId, message)
    }

    fun validateExerciseInsert(userInfo: UserInfo): String{
        val selectQuery = "SELECT MAX(date) as maxDate FROM mentions WHERE user_id = ? GROUP BY user_id"

        val statement = dbConnection.prepareStatement(selectQuery)
        statement.setLong(1, userInfo.userId)
        val resultSet = statement.executeQuery()
        val returnStr = resultSet.getString("maxDate")
        statement.close()

        return returnStr
    }

    fun selectWeeklyCntMsg(userInfo: UserInfo, dateTime: LocalDate?) {
        var startOfWeek: LocalDate
        var endOfWeek: LocalDate

        if(dateTime != null) {
            startOfWeek = dateTime.with(DayOfWeek.MONDAY)
            endOfWeek = dateTime.with(DayOfWeek.SUNDAY)
        }else{
            startOfWeek = userInfo.date.with(DayOfWeek.MONDAY)
            endOfWeek = userInfo.date.with(DayOfWeek.SUNDAY)
        }

        val selectQuery = "SELECT user_name, COUNT(user_name) as 'cnt' \n" +
                "FROM ( \n" +
                    "SELECT user_name, date \n" +
                    "FROM mentions \n" +
                    "WHERE date >= ? AND date <= ? AND chat_id = ? \n" +
                    "GROUP BY user_id, chat_id, date \n" +
                ") V \n" +
                "GROUP BY V.user_name"
        val statement = dbConnection.prepareStatement(selectQuery)
        statement.setString(1, startOfWeek.format(dateFormatter))
        statement.setString(2, endOfWeek.format(dateFormatter))
        statement.setLong(3, userInfo.chatId)

        val resultSet = statement.executeQuery()

        var sendMsg = startOfWeek.format(dateFormatter) + " ~ " + endOfWeek.format(dateFormatter) + " 집계입니다.\n\n"
        while(resultSet.next())
            sendMsg += resultSet.getString("user_name") + ": " + resultSet.getInt("cnt") + " 회\n\n"

        sendMsg += "\n한 주간 고생 많으셨습니다!"

        resultSet.close()
        statement.close()

        sendTextMessage(userInfo.chatId, sendMsg)
    }

    fun deleteUserRecentData(userInfo: UserInfo) {
        val deleteQuery = "DELETE FROM mentions " +
                "WHERE mention_id = (" +
                "   SELECT MAX(mention_id)" +
                "   FROM mentions" +
                "   WHERE user_id = ?" +
                "   GROUP BY user_id" +
                ")"
        val statement = dbConnection.prepareStatement(deleteQuery)
        statement.setLong(1, userInfo.userId)

        statement.executeUpdate()

        statement.close()

        sendTextMessage(userInfo.chatId, "${userInfo.userName} 님의 최근 데이터를 삭제하였습니다.")
    }

    fun deleteAllData(chatId: Long){
        val deleteQuery = "DELETE FROM mentions"
        val statement = dbConnection.prepareStatement(deleteQuery)
        statement.executeUpdate()

        statement.close()

        sendTextMessage(chatId, "데이터 삭제 완료")
    }


}