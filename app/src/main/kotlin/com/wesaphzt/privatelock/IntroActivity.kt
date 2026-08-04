package com.wesaphzt.privatelock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * First-run introduction.
 *
 * Replaces the previous dependency on `com.github.paolorotolo.appintro:5.1.0`,
 * which was resolved from JitPack and is long abandoned. Three static pages do
 * not justify a third-party library, and removing it drops both a build-time
 * repository and a supply-chain surface from a security-oriented app.
 */
class IntroActivity : AppCompatActivity() {

    private data class Page(
        val titleRes: Int,
        val descriptionRes: Int,
        val iconRes: Int,
        val backgroundRes: Int,
    )

    private val pages = listOf(
        Page(R.string.slider_page_one_title, R.string.slider_page_one_desc, R.drawable.ic_intro_lock, R.color.colorPrimary),
        Page(R.string.slider_page_two_title, R.string.slider_page_two_desc, R.drawable.ic_intro_iris, R.color.colorIntroGrey),
        Page(R.string.slider_page_three_title, R.string.slider_page_three_desc, R.drawable.ic_intro_shield, R.color.colorIntroGreen),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intro)

        val pager = findViewById<ViewPager2>(R.id.introPager)
        val done = findViewById<Button>(R.id.introDone)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.introRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        pager.adapter = IntroAdapter()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                done.setText(if (position == pages.lastIndex) R.string.intro_done else R.string.intro_next)
            }
        })

        done.setOnClickListener {
            val next = pager.currentItem + 1
            if (next < pages.size) pager.currentItem = next else finish()
        }
    }

    private inner class IntroAdapter : RecyclerView.Adapter<IntroAdapter.PageHolder>() {
        inner class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
            val root: View = view.findViewById(R.id.introPageRoot)
            val icon: ImageView = view.findViewById(R.id.introIcon)
            val title: TextView = view.findViewById(R.id.introTitle)
            val description: TextView = view.findViewById(R.id.introDescription)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_intro_page, parent, false),
        )

        override fun getItemCount() = pages.size

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val page = pages[position]
            holder.root.setBackgroundColor(getColor(page.backgroundRes))
            holder.icon.setImageResource(page.iconRes)
            holder.title.setText(page.titleRes)
            holder.description.setText(page.descriptionRes)
        }
    }
}
