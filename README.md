# MahsaNG

MahsaNG is not just a V2rayNG client, this beast of a VPN aims to provide free and uncensored internet access to the people who need it. It uses Rotating configs, integrated Psiphon and WARP, Fragmentation, MUX, FakeHost, and more to make internet censorship almost impossible.

<a href="https://play.google.com/store/apps/details?id=com.MahsaNet.MahsaNG&hl=en-US">
    <img src="playstorebadge_en.svg" width=239px height=71px alt="Get it on the play store"> <!--https://github.com/pioug/google-play-badges-->
</a> <br>

<a href="https://github.com/GFW-knocker/MahsaNG/releases/latest">
    <img src="https://img.shields.io/badge/Download_from-Github_releases-blue" alt="Download from github releases">
</a><a href="https://t.me/mahsa_net">
    <img src="https://telegram-badge.vercel.app/api/telegram-badge?channelId=@mahsa_net&style=social" alt="Telegram" />
</a>

<img src="https://github.com/GFW-knocker/MahsaNG/blob/master/Mahsa_logo.jpg?raw=true" width="480" style="border-radius: 5px;">

# Table of contents

- [What is MahsaNG](#mahsang)
- [What's the idea?](#whats-the-idea)
- [Differences with V2rayNG](#differences-with-v2rayng)
- [Building from source](#building-from-source)
- [Story of Mahsa...](#story-of-mahsa)

# What's the idea?

This is a client-side of the project [Segaro Dream](https://github.com/GFW-knocker/Segaro_Dream) 
.

Basically, people who have bought or made configs donate it to MahsaNG. Then, It's available to many MahsaNG users worldwide, but to decrease load on the servers and make the app more manageable, MahsaNG allows no more than 25 configs per user.

Also, Donated configs are continuously monitored and redistributed to users based on client feedback.

This project is decentralized and non-profit. It is a labor of love.


# Differences with V2rayNG
- VPN providers can use ADS service to make income instead of directly selling them.
- Everyone can share their unused vps bandwidth to help others connect.
- We prevent free configs from being stolen and resold.
- Automatic feedback for refining config collection at central servers
- Option for DNS over HTTPS
- Powerful TLS Fragmentor
- HTTP & WS fake Host
- Rotating Configs
- Random Subdomain
- And much more
# Building from source

It is not possible to build a standalone APK directly from source.

### Why?

Some parts of the project like the part responsible for encrypting configs had to stay proprietary to prevent configs from being resold.

### What parts?

Only 2:

- Parts responsible for encrypting/decrypting configs
- Classes responsible for authentication with the [Mahsa Server](https://www.mahsaserver.com/) <br>

All other technology used to bypass filtering is willingly published in [Mahsa Xray-Core](https://github.com/GFW-knocker/Xray-core), including but not limited to Wireguard Noise, YouTube Direct , TLS & HTTPS & DoH fragmenter and FakeHost.

### NikaNG


<a href="https://github.com/mahsanet/NikaNG"><img src="https://img.shields.io/badge/NikaNG_on-Github-blue" alt="NikaNG on Github"></a>

NikaNG is a fork of V2rayNG with the Mahsa Xray-Core. It is entirely FOSS and can be built.

If you're interested in Mahsa Core as a developer, looking for a more FOSS alternative to MahsaNG, or anything else, then NikaNG is for you.

>[!IMPORTANT]
>
>  You need to place [libv2ray.aar](https://github.com/GFW-knocker/AndroidLibXrayLite) in the `v2rayng/apps/libs` folder before compiling

# Story of Mahsa

On 16 September 2022, 22-year-old Kurdish-Iranian woman Mahsa Amini, also known as Jina Amini, died in a hospital in Tehran, Iran, under suspicious circumstances. The Guidance Patrol, the religious morality police of Iran's government, had arrested Amini for allegedly not wearing the hijab in accordance with government standards. [Click to view page on Wikipedia](https://en.wikipedia.org/wiki/Death_of_Mahsa_Amini)

After the death of Mahsa Amini, protests began, demanding an end to the laws of hijab and overall the Islamic republic, but the IRGC started using a lot of violence against protesters without even listening to them. Over 530 young people were killed, and over 22,000 were arrested.

As a sign of respect to all of those who lost lives in that uprising, this project was named after Mahsa Amini.

>[!NOTE]
>
>In the January 2026 protests, over 40,000 Iranians lost their lives due to their corrupt government. Don't forget about them!