package com.example.firetvott

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.ScaleAnimation
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class OttAdapter(
    private val items: List<OttService>,
    private val onClick: (OttService) -> Unit
) : RecyclerView.Adapter<OttAdapter.CardViewHolder>() {

    inner class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: View = itemView.findViewById(R.id.cardRoot)
        val title: TextView = itemView.findViewById(R.id.cardTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.card_ott, parent, false)
        return CardViewHolder(view)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        val service = items[position]
        holder.title.text = service.name
        try {
            holder.card.setBackgroundColor(Color.parseColor(service.colorHex))
        } catch (e: IllegalArgumentException) {
            holder.card.setBackgroundColor(Color.DKGRAY)
        }

        holder.card.setOnClickListener { onClick(service) }

        // Gentle scale-up on D-pad focus so navigation is legible from the couch.
        holder.card.setOnFocusChangeListener { view, hasFocus ->
            val scale = if (hasFocus) 1.12f else 1.0f
            val anim = ScaleAnimation(
                if (hasFocus) 1.0f else 1.12f, scale,
                if (hasFocus) 1.0f else 1.12f, scale,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 150
                fillAfter = true
            }
            view.startAnimation(anim)
            view.bringToFront()
            view.elevation = if (hasFocus) 16f else 2f
        }
    }

    override fun getItemCount(): Int = items.size
}
