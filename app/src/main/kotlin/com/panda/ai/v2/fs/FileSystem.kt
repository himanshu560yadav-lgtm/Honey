package com.panda.ai.v2.fs

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class FileSystem(context: Context, workspaceName: String = "agent_workspace") {

    val workspaceDir: File
    private val todoFile: File
    private val resultsFile: File

    companion object {
        private const val TAG = "FileSystem"
    }

    init {
        val baseDir = context.filesDir
        workspaceDir = File(baseDir, workspaceName)

        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs()
            Log.i(TAG, "Created new workspace directory at: ${workspaceDir.absolutePath}")
        } else {
            Log.w(TAG, "Workspace directory '$workspaceName' already exists. Reusing it.")
        }

        archiveOldTodoFile()

        this.todoFile = File(workspaceDir, "todo.md")
        this.resultsFile = File(workspaceDir, "results.md")

        try {
            if (this.todoFile.exists()) this.todoFile.delete()
            this.todoFile.createNewFile()
            if (!this.resultsFile.exists()) this.resultsFile.createNewFile()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create initial files in workspace.", e)
            throw e
        }
    }

    private fun archiveOldTodoFile() {
        val oldTodoFile = File(workspaceDir, "todo.md")
        if (oldTodoFile.exists() && oldTodoFile.length() > 0) {
            val timestamp = System.currentTimeMillis()
            val archiveFileName = "todo_ARCHIVED_$timestamp.md"
            val archiveFile = File(workspaceDir, archiveFileName)
            try {
                if (oldTodoFile.renameTo(archiveFile)) {
                    Log.i(TAG, "Successfully archived old todo.md to $archiveFileName")
                } else {
                    Log.w(TAG, "Failed to archive old todo.md.")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security error while trying to archive todo.md.", e)
            }
        }
    }

    private fun isValidFilename(fileName: String): Boolean {
        val pattern = Regex("^[a-zA-Z0-9_-]+\\.(md|txt)$")
        return fileName.matches(pattern)
    }

    suspend fun readFile(fileName: String): String = withContext(Dispatchers.IO) {
        if (!isValidFilename(fileName)) {
            return@withContext "Error: Invalid filename. Only alphanumeric .md or .txt files are allowed."
        }
        val file = File(workspaceDir, fileName)
        if (!file.exists()) return@withContext "Error: File '$fileName' not found."
        return@withContext try {
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            Log.e(TAG, "Error reading file: $fileName", e)
            "Error: Could not read file '$fileName'."
        }
    }

    suspend fun writeFile(fileName: String, content: String): Boolean = withContext(Dispatchers.IO) {
        if (!isValidFilename(fileName)) {
            Log.e(TAG, "Invalid filename for write: $fileName")
            return@withContext false
        }
        return@withContext try {
            val file = File(workspaceDir, fileName)
            file.writeText(content, Charsets.UTF_8)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error writing to file: $fileName", e)
            false
        }
    }

    suspend fun appendFile(fileName: String, content: String): Boolean = withContext(Dispatchers.IO) {
        if (!isValidFilename(fileName)) {
            Log.e(TAG, "Invalid filename for append: $fileName")
            return@withContext false
        }
        val file = File(workspaceDir, fileName)
        if (!file.exists()) Log.w(TAG, "File '$fileName' not found for append. A new file will be created.")
        return@withContext try {
            file.appendText(content, Charsets.UTF_8)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error appending to file: $fileName", e)
            false
        }
    }

    fun describe(): String {
        return try {
            val files = workspaceDir.listFiles { file ->
                file.isFile && !file.name.startsWith("todo_ARCHIVED_")
            }
            if (files.isNullOrEmpty()) return "The file system is empty."
            files.joinToString("\n") { file ->
                try {
                    val lineCount = file.readLines().size
                    "- ${file.name} — $lineCount lines"
                } catch (e: IOException) {
                    "- ${file.name} — [error reading file]"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error describing file system", e)
            "Error: Could not describe file system."
        }
    }

    fun getTodoContents(): String {
        return try {
            if (todoFile.exists()) todoFile.readText(Charsets.UTF_8) else ""
        } catch (e: IOException) {
            Log.e(TAG, "Could not read todo.md", e)
            ""
        }
    }
}