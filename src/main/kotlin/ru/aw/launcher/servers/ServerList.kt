package ru.aw.launcher.servers

import ru.aw.launcher.core.Log
import ru.aw.launcher.core.writeAtomically
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes

internal sealed interface Nbt {
    data class ByteTag(val value: Byte) : Nbt
    data class ShortTag(val value: Short) : Nbt
    data class IntTag(val value: Int) : Nbt
    data class LongTag(val value: Long) : Nbt
    data class FloatTag(val value: Float) : Nbt
    data class DoubleTag(val value: Double) : Nbt
    class ByteArrayTag(val value: ByteArray) : Nbt
    data class StringTag(val value: String) : Nbt
    data class ListTag(val type: Int, val items: List<Nbt>) : Nbt
    data class CompoundTag(val entries: Map<String, Nbt>) : Nbt
    class IntArrayTag(val value: IntArray) : Nbt
    class LongArrayTag(val value: LongArray) : Nbt

    companion object {
        private const val END = 0
        private const val COMPOUND = 10
        private const val MAX_DEPTH = 64

        fun readRoot(bytes: ByteArray): CompoundTag {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readUnsignedByte() != COMPOUND) throw IOException("не NBT: корень не compound")
            input.readUTF()
            return read(COMPOUND, input, 0) as CompoundTag
        }

        fun writeRoot(root: CompoundTag): ByteArray {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { output ->
                output.writeByte(COMPOUND)
                output.writeUTF("")
                write(root, output)
            }
            return buffer.toByteArray()
        }

        private fun read(type: Int, input: DataInput, depth: Int): Nbt {
            if (depth > MAX_DEPTH) throw IOException("NBT слишком глубокий")
            return when (type) {
                1 -> ByteTag(input.readByte())
                2 -> ShortTag(input.readShort())
                3 -> IntTag(input.readInt())
                4 -> LongTag(input.readLong())
                5 -> FloatTag(input.readFloat())
                6 -> DoubleTag(input.readDouble())
                7 -> ByteArrayTag(ByteArray(length(input)).also(input::readFully))
                8 -> StringTag(input.readUTF())
                9 -> {
                    val itemType = input.readUnsignedByte()
                    val count = length(input)
                    ListTag(itemType, List(count) { read(itemType, input, depth + 1) })
                }
                COMPOUND -> {
                    val entries = LinkedHashMap<String, Nbt>()
                    while (true) {
                        val tagType = input.readUnsignedByte()
                        if (tagType == END) break
                        entries[input.readUTF()] = read(tagType, input, depth + 1)
                    }
                    CompoundTag(entries)
                }
                11 -> IntArrayTag(IntArray(length(input)) { input.readInt() })
                12 -> LongArrayTag(LongArray(length(input)) { input.readLong() })
                else -> throw IOException("неизвестный тег NBT $type")
            }
        }

        private fun length(input: DataInput): Int =
            input.readInt().also { if (it !in 0..(1 shl 24)) throw IOException("NBT: длина $it") }

        private fun typeOf(tag: Nbt): Int = when (tag) {
            is ByteTag -> 1
            is ShortTag -> 2
            is IntTag -> 3
            is LongTag -> 4
            is FloatTag -> 5
            is DoubleTag -> 6
            is ByteArrayTag -> 7
            is StringTag -> 8
            is ListTag -> 9
            is CompoundTag -> COMPOUND
            is IntArrayTag -> 11
            is LongArrayTag -> 12
        }

        private fun write(tag: Nbt, output: DataOutput) {
            when (tag) {
                is ByteTag -> output.writeByte(tag.value.toInt())
                is ShortTag -> output.writeShort(tag.value.toInt())
                is IntTag -> output.writeInt(tag.value)
                is LongTag -> output.writeLong(tag.value)
                is FloatTag -> output.writeFloat(tag.value)
                is DoubleTag -> output.writeDouble(tag.value)
                is ByteArrayTag -> {
                    output.writeInt(tag.value.size)
                    output.write(tag.value)
                }
                is StringTag -> output.writeUTF(tag.value)
                is ListTag -> {
                    output.writeByte(if (tag.items.isEmpty()) tag.type else typeOf(tag.items.first()))
                    output.writeInt(tag.items.size)
                    tag.items.forEach { write(it, output) }
                }
                is CompoundTag -> {
                    tag.entries.forEach { (name, value) ->
                        output.writeByte(typeOf(value))
                        output.writeUTF(name)
                        write(value, output)
                    }
                    output.writeByte(END)
                }
                is IntArrayTag -> {
                    output.writeInt(tag.value.size)
                    tag.value.forEach(output::writeInt)
                }
                is LongArrayTag -> {
                    output.writeInt(tag.value.size)
                    tag.value.forEach(output::writeLong)
                }
            }
        }
    }
}

object ServerList {

    const val FILE_NAME = "servers.dat"
    private const val LEGACY_DEFAULT_NAME = "VirtusMine"
    private const val LEGACY_DEFAULT_ADDRESS = "mc.virtusmine.fun"

    fun ensure(gameDir: Path, name: String, address: String): Boolean {
        val file = gameDir.resolve(FILE_NAME)
        val root = if (file.exists()) Nbt.readRoot(file.readBytes()) else Nbt.CompoundTag(emptyMap())
        val servers = (root.entries["servers"] as? Nbt.ListTag)?.items.orEmpty()
        val wanted = normalize(address)
        val matching = servers.filterIsInstance<Nbt.CompoundTag>().filter { server ->
            (server.entries["ip"] as? Nbt.StringTag)?.value?.let(::normalize) == wanted
        }
        if (matching.any { !isHidden(it) }) return false
        val hidden = matching.firstOrNull()
        val entry = hidden
            ?.let { Nbt.CompoundTag(it.entries + ("name" to Nbt.StringTag(name)) + ("hidden" to Nbt.ByteTag(0))) }
            ?: Nbt.CompoundTag(linkedMapOf("name" to Nbt.StringTag(name), "ip" to Nbt.StringTag(address)))
        val rest = servers.filter { it !== hidden }
        val updated = Nbt.CompoundTag(root.entries + ("servers" to Nbt.ListTag(10, listOf(entry) + rest)))
        file.writeAtomically(Nbt.writeRoot(updated))
        return true
    }

    fun removeLegacyDefault(gameDir: Path): Boolean {
        val file = gameDir.resolve(FILE_NAME)
        if (!file.exists()) return false

        val root = Nbt.readRoot(file.readBytes())
        val servers = root.entries["servers"] as? Nbt.ListTag ?: return false
        val remaining = servers.items.filterNot { item ->
            val entries = (item as? Nbt.CompoundTag)?.entries ?: return@filterNot false
            val name = (entries["name"] as? Nbt.StringTag)?.value
            val address = (entries["ip"] as? Nbt.StringTag)?.value
            name == LEGACY_DEFAULT_NAME && address?.let(::normalize) == normalize(LEGACY_DEFAULT_ADDRESS)
        }
        if (remaining.size == servers.items.size) return false

        file.writeAtomically(Nbt.writeRoot(Nbt.CompoundTag(root.entries + ("servers" to Nbt.ListTag(servers.type, remaining)))))
        return true
    }

    private fun isHidden(server: Nbt.CompoundTag): Boolean =
        ((server.entries["hidden"] as? Nbt.ByteTag)?.value ?: 0).toInt() != 0

    internal fun normalize(address: String): String =
        address.trim().lowercase().removeSuffix(".").removeSuffix(":25565")
}
