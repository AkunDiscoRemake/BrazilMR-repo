package com.brazilmr.core.spatial

class SpatialObject(val id: Int, val owner: String, var x: Float, var y: Float, var z: Float, var size: Float, var color: Int)
class Scenario {
    private val entries = ArrayList<SpatialObject>(64)
    val objects: List<SpatialObject> get() = entries
    private var nextId = 1
    fun spawn(owner: String, x: Float, y: Float, z: Float, size: Float, color: Int): Int {
        check(entries.count { it.owner == owner } < 32 && entries.size < 64) { "Limite de objetos espaciais" }
        validate(x, y, z, size)
        val id = nextId++
        entries.add(SpatialObject(id, owner, x, y, z, size, color))
        return id
    }
    fun move(owner: String, id: Int, x: Float, y: Float, z: Float) {
        val item = entries.firstOrNull { it.id == id && it.owner == owner } ?: error("Objeto não pertence ao app")
        validate(x, y, z, item.size); item.x = x; item.y = y; item.z = z
    }
    fun remove(owner: String, id: Int) { entries.removeAll { it.id == id && it.owner == owner } }
    fun clear(owner: String) { entries.removeAll { it.owner == owner } }
    private fun validate(x: Float, y: Float, z: Float, size: Float) {
        require(x in -5f..5f && y in -5f..5f && z in -10f..-0.3f && size in 0.01f..1f)
    }
}
