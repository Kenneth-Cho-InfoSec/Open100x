package com.example.zoomhundred

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.zoomhundred.databinding.ActivityAppInfoBinding

class AppInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        if (isGooglePixelDevice()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        super.onCreate(savedInstanceState)
        binding = ActivityAppInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.githubLink.text = getString(R.string.github_profile_url)
        binding.githubLink.setOnClickListener { openGithubProfile() }
        binding.closeButton.setOnClickListener { finish() }
    }

    private fun openGithubProfile() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_profile_url))))
    }
}
