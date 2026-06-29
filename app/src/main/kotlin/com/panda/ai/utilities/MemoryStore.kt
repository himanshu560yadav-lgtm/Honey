package com.panda.ai.utilities

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

object MemoryStore {
    private const val MEMORY_FILE = "honey_memory.json"
    private val gson = Gson()

    data class Memory(
        val contacts: MutableMap<String, String> = mutableMapOf(),
        val appPatterns: MutableMap<String, List<String>> = mutableMapOf(),
        val lastTasks: MutableList<String> = mutableListOf(),
        val userPreferences: MutableMap<String, String> = mutableMapOf()
    )

    private fun getFile(context: Context): File {
        return File(context.filesDir, MEMORY_FILE)
    }

    private fun load(context: Context): Memory {
        val file = getFile(context)
        if (!file.exists()) return Memory()
        return try {
            gson.fromJson(file.readText(), Memory::class.java) ?: Memory()
        } catch (e: Exception) {
            Memory()
        }
    }

    private fun save(context: Context, memory: Memory) {
        getFile(context).writeText(gson.toJson(memory))
    }

    // Contact save karo — "Mami" -> "+91XXXXXX"
    fun saveContact(context: Context, name: String, number: String) {
        val memory = load(context)
        memory.contacts[name.lowercase()] = number
        save(context, memory)
    }

    // Contact lo
    fun getContact(context: Context, name: String): String? {
        return load(context).contacts[name.lowercase()]
    }

    // Last task save karo
    fun saveLastTask(context: Context, task: String) {
        val memory = load(context)
        memory.lastTasks.add(0, task)
        if (memory.lastTasks.size > 20) memory.lastTasks.removeLast()
        save(context, memory)
    }

    // Preference save karo
    fun savePreference(context: Context, key: String, value: String) {
        val memory = load(context)
        memory.userPreferences[key] = value
        save(context, memory)
    }

    // System prompt ke liye memory context banao
    fun getMemoryContext(context: Context): String {
        val memory = load(context)
        val sb = StringBuilder()

        if (memory.contacts.isNotEmpty()) {
            sb.append("Known contacts:\n")
            memory.contacts.forEach { (name, number) ->
                sb.append("- $name: $number\n")
            }
        }

        if (memory.lastTasks.isNotEmpty()) {
            sb.append("\nRecent tasks:\n")
            memory.lastTasks.take(5).forEach {
                sb.append("- $it\n")
            }
        }

        if (memory.userPreferences.isNotEmpty()) {
            sb.append("\nUser preferences:\n")
            memory.userPreferences.forEach { (k, v) ->
                sb.append("- $k: $v\n")
            }
        }

        return sb.toString()
    }

    // Sab clear karo
    fun clearAll(context: Context) {
        getFile(context).delete()
    }
}