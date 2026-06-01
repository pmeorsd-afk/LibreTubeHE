package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.api.obj.Subscription
import com.github.libretube.databinding.SubscriptionAvatarRowBinding
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.ui.adapters.callbacks.DiffUtilItemCallback

class SubscriptionAvatarAdapter :
    ListAdapter<Subscription, SubscriptionAvatarAdapter.ViewHolder>(DiffUtilItemCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = SubscriptionAvatarRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val subscription = getItem(position)
        holder.binding.apply {
            subscriptionName.text = subscription.name
            ImageHelper.loadImage(subscription.avatar, subscriptionAvatar, true)
            root.setOnClickListener {
                NavigationHelper.navigateChannel(root.context, subscription.url)
            }
        }
    }

    class ViewHolder(val binding: SubscriptionAvatarRowBinding) :
        RecyclerView.ViewHolder(binding.root)
}
