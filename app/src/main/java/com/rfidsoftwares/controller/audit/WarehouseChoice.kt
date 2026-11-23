package com.rfidsoftwares.controller.audit

import com.rfidsoftwares.data.frappe.WarehouseOption

/** Which warehouse the dropdown has selected when the screen opens. */
object WarehouseChoice {

    /**
     * The warehouse counted last time if it is still on the list; otherwise the one with the most
     * tagged stock (that is where an audit is most likely wanted); otherwise the first on the list.
     * Null only when there are no warehouses at all.
     */
    fun pick(options: List<WarehouseOption>, remembered: String?): WarehouseOption? {
        if (options.isEmpty()) return null
        options.firstOrNull { it.name == remembered }?.let { return it }
        // maxByOrNull keeps the first of equals, so the list's own order breaks a tie.
        return options.maxByOrNull { it.tags }
    }
}
