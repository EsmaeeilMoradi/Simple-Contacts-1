package com.simplemobiletools.contacts.pro.activities

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuItemCompat
import com.reddit.indicatorfastscroll.FastScrollItemIndicator
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.contacts.pro.R
import com.simplemobiletools.contacts.pro.adapters.SelectContactsAdapter
import com.simplemobiletools.contacts.pro.dialogs.ChangeSortingDialog
import com.simplemobiletools.contacts.pro.dialogs.FilterContactSourcesDialog
import com.simplemobiletools.contacts.pro.extensions.config
import com.simplemobiletools.contacts.pro.extensions.getContactPublicUri
import com.simplemobiletools.contacts.pro.extensions.getVisibleContactSources
import com.simplemobiletools.contacts.pro.helpers.ContactsHelper
import com.simplemobiletools.contacts.pro.helpers.getProperText
import com.simplemobiletools.contacts.pro.models.Contact
import kotlinx.android.synthetic.main.activity_select_contact.*
import java.util.*
import kotlin.collections.ArrayList

class SelectContactActivity : SimpleActivity() {
    private var specialMimeType: String? = null
    private var isSearchOpen = false
    private var searchMenuItem: MenuItem? = null
    private var contactsIgnoringSearch = ArrayList<Contact>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_contact)

        if (checkAppSideloading()) {
            return
        }

        setupViews()

        handlePermission(PERMISSION_READ_CONTACTS) {
            if (it) {
                handlePermission(PERMISSION_WRITE_CONTACTS) {
                    if (it) {
                        specialMimeType = when (intent.data) {
                            Email.CONTENT_URI -> Email.CONTENT_ITEM_TYPE
                            Phone.CONTENT_URI -> Phone.CONTENT_ITEM_TYPE
                            else -> null
                        }
                        initContacts()
                    } else {
                        toast(R.string.no_contacts_permission)
                        finish()
                    }
                }
            } else {
                toast(R.string.no_contacts_permission)
                finish()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        searchMenuItem?.collapseActionView()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_select_activity, menu)
        setupSearch(menu)
        updateMenuItemColors(menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sort -> showSortingDialog()
            R.id.filter -> showFilterDialog()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun setupSearch(menu: Menu) {
        val searchManager = getSystemService(Context.SEARCH_SERVICE) as SearchManager
        searchMenuItem = menu.findItem(R.id.search)
        (searchMenuItem!!.actionView as SearchView).apply {
            setSearchableInfo(searchManager.getSearchableInfo(componentName))
            isSubmitButtonEnabled = false
            queryHint = getString(R.string.search_contacts)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String) = false

                override fun onQueryTextChange(newText: String): Boolean {
                    if (isSearchOpen) {
                        onSearchQueryChanged(newText)
                    }
                    return true
                }
            })
        }

        MenuItemCompat.setOnActionExpandListener(searchMenuItem, object : MenuItemCompat.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem?): Boolean {
                onSearchOpened()
                isSearchOpen = true
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem?): Boolean {
                onSearchClosed()
                isSearchOpen = false
                return true
            }
        })
    }

    private fun onSearchQueryChanged(text: String) {
        val adapter = select_contact_list.adapter
        if (adapter != null && adapter is SelectContactsAdapter) {
            val shouldNormalize = text.normalizeString() == text
            val filtered = contactsIgnoringSearch.filter {
                getProperText(it.getNameToDisplay(), shouldNormalize).contains(text, true) ||
                    getProperText(it.nickname, shouldNormalize).contains(text, true) ||
                    it.doesContainPhoneNumber(text, false) ||
                    it.emails.any { it.value.contains(text, true) } ||
                    it.addresses.any { getProperText(it.value, shouldNormalize).contains(text, true) } ||
                    it.IMs.any { it.value.contains(text, true) } ||
                    getProperText(it.notes, shouldNormalize).contains(text, true) ||
                    getProperText(it.organization.company, shouldNormalize).contains(text, true) ||
                    getProperText(it.organization.jobPosition, shouldNormalize).contains(text, true) ||
                    it.websites.any { it.contains(text, true) }
            } as ArrayList

            filtered.sortBy {
                val nameToDisplay = it.getNameToDisplay()
                !getProperText(nameToDisplay, shouldNormalize).startsWith(text, true) && !nameToDisplay.contains(text, true)
            }

            if (filtered.isEmpty()) {
                select_contact_placeholder.text = getString(R.string.no_items_found)
            }

            select_contact_placeholder.beVisibleIf(filtered.isEmpty())
            adapter.updateItems(filtered, text.normalizeString())
        }
    }

    private fun onSearchOpened() {
        contactsIgnoringSearch = (select_contact_list.adapter as? SelectContactsAdapter)?.contacts ?: ArrayList()
    }

    private fun onSearchClosed() {
        (select_contact_list.adapter as? SelectContactsAdapter)?.updateItems(contactsIgnoringSearch)
    }

    private fun showSortingDialog() {
        ChangeSortingDialog(this) {
            initContacts()
        }
    }

    private fun showFilterDialog() {
        FilterContactSourcesDialog(this) {
            initContacts()
        }
    }

    private fun initContacts() {
        ContactsHelper(this).getContacts {
            if (isDestroyed || isFinishing) {
                return@getContacts
            }

            var contacts = it.filter {
                if (specialMimeType != null) {
                    val hasRequiredValues = when (specialMimeType) {
                        Email.CONTENT_ITEM_TYPE -> it.emails.isNotEmpty()
                        Phone.CONTENT_ITEM_TYPE -> it.phoneNumbers.isNotEmpty()
                        else -> true
                    }
                    !it.isPrivate() && hasRequiredValues
                } else {
                    true
                }
            } as ArrayList<Contact>

            val contactSources = getVisibleContactSources()
            contacts = contacts.filter { contactSources.contains(it.source) } as ArrayList<Contact>

            runOnUiThread {
                updatePlaceholderVisibility(contacts)
                SelectContactsAdapter(this, contacts, ArrayList(), false, select_contact_list) {
                    confirmSelection(it)
                }.apply {
                    select_contact_list.adapter = this
                }

                if (areSystemAnimationsEnabled) {
                    select_contact_list.scheduleLayoutAnimation()
                }

                letter_fastscroller.setupWithRecyclerView(select_contact_list, { position ->
                    try {
                        val name = contacts[position].getNameToDisplay()
                        val character = if (name.isNotEmpty()) name.substring(0, 1) else ""
                        FastScrollItemIndicator.Text(character.normalizeString().toUpperCase(Locale.getDefault()))
                    } catch (e: Exception) {
                        FastScrollItemIndicator.Text("")
                    }
                })
            }
        }
    }

    private fun confirmSelection(contact: Contact) {
        Intent().apply {
            data = getResultUri(contact)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setResult(RESULT_OK, this)
        }
        finish()
    }

    private fun getResultUri(contact: Contact): Uri {
        return when {
            specialMimeType != null -> {
                val contactId = ContactsHelper(this).getContactMimeTypeId(contact.id.toString(), specialMimeType!!)
                Uri.withAppendedPath(ContactsContract.Data.CONTENT_URI, contactId)
            }
            else -> getContactPublicUri(contact)
        }
    }

    private fun setupViews() {
        val adjustedPrimaryColor = getAdjustedPrimaryColor()
        select_contact_placeholder.setTextColor(config.textColor)
        select_contact_placeholder_2.setTextColor(adjustedPrimaryColor)
        select_contact_placeholder_2.underlineText()
        select_contact_placeholder_2.setOnClickListener {
            FilterContactSourcesDialog(this) {
                initContacts()
            }
        }

        letter_fastscroller?.textColor = config.textColor.getColorStateList()
        letter_fastscroller?.pressedTextColor = adjustedPrimaryColor
        letter_fastscroller_thumb?.fontSize = getTextSize()
        letter_fastscroller_thumb?.textColor = adjustedPrimaryColor.getContrastColor()
        letter_fastscroller_thumb?.thumbColor = adjustedPrimaryColor.getColorStateList()
        letter_fastscroller_thumb.setupWithFastScroller(letter_fastscroller)
    }

    private fun updatePlaceholderVisibility(contacts: ArrayList<Contact>) {
        select_contact_list.beVisibleIf(contacts.isNotEmpty())
        select_contact_placeholder_2.beVisibleIf(contacts.isEmpty())
        select_contact_placeholder.beVisibleIf(contacts.isEmpty())
        select_contact_placeholder.setText(
            when (specialMimeType) {
                Email.CONTENT_ITEM_TYPE -> R.string.no_contacts_with_emails
                Phone.CONTENT_ITEM_TYPE -> R.string.no_contacts_with_phone_numbers
                else -> R.string.no_contacts_found
            }
        )
    }
}
