package com.kunzisoft.keepass.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import com.kunzisoft.keepass.database.element.DateInstant
import com.kunzisoft.keepass.database.element.icon.IconImage
import com.kunzisoft.keepass.model.DataDate
import com.kunzisoft.keepass.model.DataTime

abstract class NodeEditViewModel : ViewModel() {

    val requestIconSelection : LiveData<IconImage> get() = _requestIconSelection
    private val _requestIconSelection = SingleLiveEvent<IconImage>()
    val onIconSelected : LiveData<IconImage> get() = _onIconSelected
    private val _onIconSelected = SingleLiveEvent<IconImage>()

    val requestDateTimeSelection : LiveData<DateInstant> get() = _requestDateTimeSelection
    private val _requestDateTimeSelection = SingleLiveEvent<DateInstant>()
    val onDateSelected : LiveData<DataDate> get() = _onDateSelected
    private val _onDateSelected = SingleLiveEvent<DataDate>()
    val onTimeSelected : LiveData<DataTime> get() = _onTimeSelected
    private val _onTimeSelected = SingleLiveEvent<DataTime>()

    fun requestIconSelection(oldIconImage: IconImage) {
        _requestIconSelection.value = oldIconImage
    }

    fun selectIcon(iconImage: IconImage) {
        _onIconSelected.value = iconImage
    }

    fun requestDateTimeSelection(dateInstant: DateInstant) {
        _requestDateTimeSelection.value = dateInstant
    }

    fun selectDate(date: DataDate) {
        _onDateSelected.value = date
    }

    fun selectTime(dataTime: DataTime) {
        _onTimeSelected.value = dataTime
    }

}
