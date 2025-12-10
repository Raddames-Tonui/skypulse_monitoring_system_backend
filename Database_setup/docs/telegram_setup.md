# Telegram Group Notifications Setup and Usage

This README.md explains how to send notifications to a Telegram group using an existing bot, starting from group setup.

---

## **1. Make sure your bot exists**

* You already created a bot via **BotFather**.
* You have a **bot token** (something like `123456789:ABCdefGhIJKlmnoPQRstuvWXyz`).

---

### **2. Add your bot to the group**

* Go to your **Telegram group** (your frontend group).
* Add the bot as a member.
* If you want the bot to send messages, make it an **admin** or give it **permission to send messages**.

---

### **3. Get the group chat ID**

* Telegram uses **chat IDs** to send messages. Groups have **negative chat IDs**.
* There are two ways to get it:

#### **Option A: Using your bot**

1. Send any message in the group (or a test message).
2. Call the Telegram API to get updates:

   ```
   https://api.telegram.org/bot<YOUR_BOT_TOKEN>/getUpdates

   ```
3. Look at the JSON response:

   ```
   {
     "ok": true,
     "result": [
       {
         "update_id": 123456789,
         "message": {
           "message_id": 1,
           "from": { "id": 1111111, "is_bot": false, "first_name": "Alice" },
           "chat": { "id": -987654321, "title": "Frontend Team", "type": "group" },
           "text": "Hello"
         }
       }
     ]
   }

   ```

   The **chat ID** is `-987654321`. ✅

#### **Option B: Use a bot command**

* Send a command in the group like `/start`.
* Call `/getUpdates` as above to see the `chat.id`.

---

### **4. Configure your system**

* In your database, for the `contact_group_contacts` table:

    * `type = TELEGRAM`
    * `value = <group chat ID>` (the negative number you got)
* In `RecipientResolver`, when you resolve group-level recipients, this chat ID will be used.

---

### **5. Test sending**

* You can test manually with **curl**:

```
curl -X POST "https://api.telegram.org/bot<YOUR_BOT_TOKEN>/sendMessage" \
-H "Content-Type: application/json" \
-d '{"chat_id": "-987654321", "text": "Test message from bot"}'

```

* If the bot sends the message to the group, your integration is working.

---

### **6. Now your NotificationProcessorTask**

* It will call `TelegramSender.send()` with the **chat ID**.
* The bot will automatically post messages to the group.

---

💡 **Important notes**

* Bots cannot read messages in groups unless **privacy mode** is disabled in BotFather (only needed if you want to handle replies).
* All your group notifications should use the **chat ID**, not individual user IDs.

### Diagram of Event Flow

```
[Event Trigger] --> [NotificationProcessorTask] --> [RecipientResolver] --> [TelegramSender] --> [Telegram Group]
```

* **Event Trigger**: System or user action triggers a notification event.
* **NotificationProcessorTask**: Processes pending events, fetches templates, and schedules sends.
* **RecipientResolver**: Resolves which users or groups should receive the message.
* **TelegramSender**: Sends the notification to the Telegram API using the bot.
* **Telegram Group**: The target group receives the message.
