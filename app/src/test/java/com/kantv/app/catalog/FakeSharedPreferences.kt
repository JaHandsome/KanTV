package com.kantv.app.catalog

import android.content.SharedPreferences

/** JVM 单测用的内存版 SharedPreferences；生产代码只依赖接口，不触碰 Android 实现。 */
class FakeSharedPreferences : SharedPreferences {
    private val values = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<*>)?.filterIsInstanceTo(LinkedHashSet()) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor(values)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private class Editor(
        private val values: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        private val updates = LinkedHashMap<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?) = apply { updates[key.orEmpty()] = value }

        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { updates[key.orEmpty()] = values?.toSet() }

        override fun putInt(key: String?, value: Int) = apply { updates[key.orEmpty()] = value }

        override fun putLong(key: String?, value: Long) = apply { updates[key.orEmpty()] = value }

        override fun putFloat(key: String?, value: Float) = apply { updates[key.orEmpty()] = value }

        override fun putBoolean(key: String?, value: Boolean) = apply { updates[key.orEmpty()] = value }

        override fun remove(key: String?) = apply { updates[key.orEmpty()] = Removed }

        override fun clear() = apply { clearRequested = true }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() = applyChanges()

        private fun applyChanges() {
            if (clearRequested) values.clear()
            updates.forEach { (key, value) ->
                if (value === Removed) values.remove(key) else values[key] = value
            }
        }

        private object Removed
    }
}
