package com.brazilmr.lua

import org.luaj.vm2.*
import org.luaj.vm2.lib.DebugLib

/** Error rather than LuaError: pcall/xpcall cannot swallow a budget violation. */
class ScriptBudgetExceeded(message: String) : Error(message)

internal class ExecutionBudget : DebugLib() {
    private var remaining = 0
    private var deadline = 0L
    private var depth = 0
    private val stacks = arrayOfNulls<Array<LuaValue?>>(64)
    @Volatile var cancelled = false
    fun begin(timeoutMillis: Long = 50) {
        check(!cancelled) { "Script encerrado" }
        remaining = 100_000; deadline = System.nanoTime() + timeoutMillis * 1_000_000
        stacks.fill(null); depth = 0
    }
    fun checkBudget() {
        if (cancelled || Thread.currentThread().isInterrupted) throw ScriptBudgetExceeded("Execução cancelada")
        if (remaining <= 0 || System.nanoTime() > deadline) throw ScriptBudgetExceeded("Limite de instruções ou tempo de execução")
    }
    override fun call(modname: LuaValue, env: LuaValue): LuaValue {
        // Do not load or expose debug.sethook, upvalues or the registry.
        env.checkglobals().debuglib = this
        return LuaValue.NIL
    }
    override fun onCall(function: LuaFunction) { push(null) }
    override fun onCall(closure: LuaClosure, varargs: Varargs, stack: Array<LuaValue?>) { push(stack) }
    private fun push(stack: Array<LuaValue?>?) {
        if (depth >= stacks.size) throw ScriptBudgetExceeded("Limite de profundidade Lua")
        stacks[depth++] = stack
    }
    override fun onReturn() { if (depth > 0) stacks[--depth] = null }
    override fun traceback(level: Int): String = "Brazil MR · Lua sandbox"
    override fun onInstruction(pc: Int, values: Varargs, top: Int) {
        remaining--
        checkBudget()
        // Bound intermediate concatenations before a doubling loop can allocate a huge string.
        val stack = if (depth > 0) stacks[depth - 1] else null
        if (stack != null) for (i in stack.indices) {
            val value = stack[i] ?: continue
            if (value.type() == LuaValue.TSTRING && value.rawlen() > 16_384) throw ScriptBudgetExceeded("String maior que 16 KiB")
            if (remaining % 128 == 0 && value.istable() && value.checktable().keyCount() > 4096) throw ScriptBudgetExceeded("Tabela maior que 4096 entradas")
        }
    }
}
