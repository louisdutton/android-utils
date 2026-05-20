/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.adapters

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SortedList
import androidx.recyclerview.widget.SortedListAdapterCallback
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.model.DatabaseFile

class FileDatabaseHistoryAdapter(context: Context)
    : RecyclerView.Adapter<FileDatabaseHistoryAdapter.FileDatabaseHistoryViewHolder>() {

    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private var fileItemOpenListener: ((DatabaseFile) -> Unit)? = null

    private val mListPosition = mutableListOf<SuperDatabaseFile>()
    private val mSortedListDatabaseFiles = SortedList(
        SuperDatabaseFile::class.java,
        object: SortedListAdapterCallback<SuperDatabaseFile>(this) {
            override fun compare(item1: SuperDatabaseFile, item2: SuperDatabaseFile): Int {
                val indexItem1 = mListPosition.indexOf(item1)
                val indexItem2 = mListPosition.indexOf(item2)
                return if (indexItem1 == -1 && indexItem2 == -1)
                    -1
                else if (indexItem1 < indexItem2)
                    -1
                else if (indexItem1 > indexItem2)
                    1
                else
                    0
            }

            override fun areContentsTheSame(
                oldItem: SuperDatabaseFile,
                newItem: SuperDatabaseFile
            ): Boolean {
                val oldDatabaseFile = oldItem.databaseFile
                val newDatabaseFile = newItem.databaseFile
                return oldDatabaseFile.databaseUri == newDatabaseFile.databaseUri
                        && oldDatabaseFile.databaseDecodedPath == newDatabaseFile.databaseDecodedPath
                        && oldDatabaseFile.databaseAlias == newDatabaseFile.databaseAlias
                        && oldDatabaseFile.databaseFileExists == newDatabaseFile.databaseFileExists
            }

            override fun areItemsTheSame(
                item1: SuperDatabaseFile,
                item2: SuperDatabaseFile
            ): Boolean {
                return item1.databaseFile == item2.databaseFile
            }
        }
    )

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): FileDatabaseHistoryViewHolder {
        val view = inflater.inflate(R.layout.item_file_info, parent, false)
        return FileDatabaseHistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileDatabaseHistoryViewHolder, position: Int) {
        val databaseFile = mSortedListDatabaseFiles[position].databaseFile

        holder.fileContainer.setOnClickListener {
            fileItemOpenListener?.invoke(databaseFile)
        }
        holder.fileAlias.text = databaseFile.databaseAlias
            ?: databaseFile.databaseDecodedPath?.substringAfterLast('/')
            ?: holder.itemView.context.getString(R.string.database)

        if (databaseFile.databaseFileExists) {
            holder.fileInformationButton.clearColorFilter()
        } else {
            holder.fileInformationButton.setColorFilter(Color.RED, PorterDuff.Mode.MULTIPLY)
        }
    }

    override fun getItemCount(): Int {
        return mSortedListDatabaseFiles.size()
    }

    fun clearDatabaseFileHistoryList() {
        mListPosition.clear()
        mSortedListDatabaseFiles.clear()
    }

    fun addDatabaseFileHistory(fileDatabaseHistoryToAdd: DatabaseFile) {
        val superToAdd = SuperDatabaseFile(fileDatabaseHistoryToAdd)
        mListPosition.add(0, superToAdd)
        mSortedListDatabaseFiles.add(superToAdd)
    }

    fun updateDatabaseFileHistory(fileDatabaseHistoryToUpdate: DatabaseFile) {
        val superToUpdate = SuperDatabaseFile(fileDatabaseHistoryToUpdate)
        val index = mListPosition.indexOf(superToUpdate)
        if (mListPosition.remove(superToUpdate)) {
            mListPosition.add(index, superToUpdate)
        }
        mSortedListDatabaseFiles.updateItemAt(index, superToUpdate)
    }

    fun deleteDatabaseFileHistory(fileDatabaseHistoryToDelete: DatabaseFile) {
        val superToDelete = SuperDatabaseFile(fileDatabaseHistoryToDelete)
        val index = mListPosition.indexOf(superToDelete)
        mListPosition.remove(superToDelete)
        mSortedListDatabaseFiles.removeItemAt(index)
    }

    fun replaceAllDatabaseFileHistoryList(listFileDatabaseHistoryToAdd: List<DatabaseFile>) {
        val superMapToReplace = listFileDatabaseHistoryToAdd.map {
            SuperDatabaseFile(it)
        }
        mListPosition.clear()
        mListPosition.addAll(superMapToReplace)
        mSortedListDatabaseFiles.replaceAll(superMapToReplace)
    }

    fun setOnFileDatabaseHistoryOpenListener(listener: ((DatabaseFile) -> Unit)?) {
        this.fileItemOpenListener = listener
    }

    private inner class SuperDatabaseFile(
        var databaseFile: DatabaseFile
    ) {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SuperDatabaseFile) return false

            if (databaseFile != other.databaseFile) return false

            return true
        }

        override fun hashCode(): Int {
            return databaseFile.hashCode()
        }
    }

    class FileDatabaseHistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        var fileContainer: ViewGroup = itemView.findViewById(R.id.file_container_basic_info)
        var fileAlias: TextView = itemView.findViewById(R.id.file_alias)
        var fileInformationButton: ImageView = itemView.findViewById(R.id.file_information_button)
    }
}
