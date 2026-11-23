package com.rfidsoftwares.controller.audit

import com.rfidsoftwares.data.frappe.WarehouseOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WarehouseChoiceTest {

    private val stores = WarehouseOption("Stores - A", 2)
    private val testStore = WarehouseOption("RFID Test Store - A", 5)
    private val goods = WarehouseOption("Finished Goods - A", 0)

    @Test
    fun theWarehouseCountedLastTimeIsSelected() {
        assertEquals(goods, WarehouseChoice.pick(listOf(stores, testStore, goods), remembered = "Finished Goods - A"))
    }

    @Test
    fun withNothingRememberedTheBusiestWarehouseIsSelected() {
        assertEquals(testStore, WarehouseChoice.pick(listOf(goods, stores, testStore), remembered = null))
    }

    @Test
    fun aRememberedWarehouseThatIsGoneFallsBackToTheBusiest() {
        assertEquals(testStore, WarehouseChoice.pick(listOf(stores, testStore), remembered = "Deleted - A"))
    }

    @Test
    fun whenNoneHaveTagsTheFirstOnTheListIsSelected() {
        val a = WarehouseOption("A - A")
        val b = WarehouseOption("B - A")
        assertEquals(a, WarehouseChoice.pick(listOf(a, b), remembered = null))
    }

    @Test
    fun noWarehousesMeansNoSelection() {
        assertNull(WarehouseChoice.pick(emptyList(), remembered = "Stores - A"))
    }

    @Test
    fun theDropdownShowsHowManyTagsAreInEachWarehouse() {
        assertEquals("Stores - A (2 tags)", stores.label)
        assertEquals("Stores - A (1 tag)", WarehouseOption("Stores - A", 1).label)
        assertEquals("Finished Goods - A", goods.label)
    }
}
