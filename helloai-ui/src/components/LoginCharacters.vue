<template>
  <div class="characters-stage" ref="stageRef">
    <!-- ====== ROBOT 1: 绿芽 (Sprout) - 最高 180px ====== -->
    <div
      class="char-slot slot-sprout"
      tabindex="0"
      role="img"
      aria-label="绿芽机器人：白色圆润机身，绿色S型螺旋标志，头顶环形天线，绿色大眼睛"
    >
      <svg
        width="120" height="200" viewBox="0 0 120 200"
        class="char-svg"
        xmlns="http://www.w3.org/2000/svg"
      >
        <defs>
          <linearGradient id="g-sprout-body" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#FFFFFF"/>
            <stop offset="100%" stop-color="#F0FDF4"/>
          </linearGradient>
          <linearGradient id="g-sprout-glass" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stop-color="white" stop-opacity="0.35"/>
            <stop offset="100%" stop-color="white" stop-opacity="0"/>
          </linearGradient>
          <linearGradient id="g-sprout-face" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#F0FFF4"/>
            <stop offset="100%" stop-color="#DCFCE7"/>
          </linearGradient>
          <radialGradient id="g-sprout-eye-shine" cx="35%" cy="35%" r="60%">
            <stop offset="0%" stop-color="white" stop-opacity="0.9"/>
            <stop offset="100%" stop-color="white" stop-opacity="0"/>
          </radialGradient>
        </defs>
        <!-- Ground shadow -->
        <ellipse cx="60" cy="190" rx="36" ry="6" fill="rgba(0,0,0,0.08)"/>
        <!-- Feet -->
        <ellipse cx="38" cy="182" rx="12" ry="6" fill="#E5E7EB" stroke="#374151" stroke-width="1.2"/>
        <ellipse cx="82" cy="182" rx="12" ry="6" fill="#E5E7EB" stroke="#374151" stroke-width="1.2"/>
        <!-- Legs -->
        <rect x="28" y="168" width="20" height="16" rx="6" fill="url(#g-sprout-body)" stroke="#374151" stroke-width="1.2"/>
        <rect x="72" y="168" width="20" height="16" rx="6" fill="url(#g-sprout-body)" stroke="#374151" stroke-width="1.2"/>
        <!-- Arms -->
        <rect x="4" y="96" width="18" height="12" rx="6" fill="url(#g-sprout-body)" stroke="#374151" stroke-width="1.2"/>
        <rect x="2" y="104" width="12" height="28" rx="6" fill="#E5E7EB" stroke="#374151" stroke-width="0.8"/>
        <rect x="98" y="96" width="18" height="12" rx="6" fill="url(#g-sprout-body)" stroke="#374151" stroke-width="1.2"/>
        <rect x="106" y="104" width="12" height="28" rx="6" fill="#E5E7EB" stroke="#374151" stroke-width="0.8"/>
        <!-- Body -->
        <rect x="20" y="84" width="80" height="86" rx="18" fill="url(#g-sprout-body)" stroke="#374151" stroke-width="1.5"/>
        <!-- Chest S-spiral logo -->
        <g>
          <path d="M 48 108 C 48 100, 56 100, 56 108 L 56 118 C 56 126, 48 126, 48 118 L 48 127 C 48 135, 60 135, 60 127 L 60 118"
                fill="none" stroke="#4ADE80" stroke-width="3.5" stroke-linecap="round"/>
        </g>
        <!-- Body glass reflection -->
        <rect x="20" y="84" width="80" height="86" rx="18" fill="url(#g-sprout-glass)" opacity="0.4"/>
        <!-- Neck -->
        <rect x="46" y="74" width="28" height="16" rx="5" fill="#E5E7EB" stroke="#374151" stroke-width="0.8"/>
        <!-- Head group -->
        <g>
          <!-- Antenna -->
          <line x1="60" y1="16" x2="60" y2="30" stroke="#374151" stroke-width="1.8"/>
          <circle cx="60" cy="10" r="7" fill="none" stroke="#374151" stroke-width="1.8"/>
          <circle cx="60" cy="10" r="3" fill="#4ADE80" opacity="0.4"/>
          <!-- Head -->
          <rect x="22" y="28" width="76" height="48" rx="16" fill="url(#g-sprout-body)" stroke="#374151" stroke-width="1.5"/>
          <!-- Face screen -->
          <rect x="28" y="33" width="64" height="38" rx="8" fill="url(#g-sprout-face)" stroke="#4ADE80" stroke-opacity="0.3" stroke-width="0.8"/>
          <rect x="28" y="33" width="64" height="38" rx="8" fill="url(#g-sprout-glass)" opacity="0.3"/>
          <!-- Left eye -->
          <circle cx="44" cy="52" r="9" fill="white" stroke="#4ADE80" stroke-width="1.5"/>
          <circle cx="44" cy="52" r="6" fill="#4ADE80"/>
          <circle cx="44" cy="52" r="3.5" fill="#166534"/>
          <circle cx="42" cy="50" r="2.5" fill="url(#g-sprout-eye-shine)"/>
          <!-- Right eye -->
          <circle cx="76" cy="52" r="9" fill="white" stroke="#4ADE80" stroke-width="1.5"/>
          <circle cx="76" cy="52" r="6" fill="#4ADE80"/>
          <circle cx="76" cy="52" r="3.5" fill="#166534"/>
          <circle cx="74" cy="50" r="2.5" fill="url(#g-sprout-eye-shine)"/>
          <!-- Smile mouth -->
          <path d="M 46 56 Q 60 66 74 56" fill="none" stroke="#374151" stroke-width="1.8" stroke-linecap="round"/>
          <!-- Cheek blush -->
          <ellipse cx="30" cy="62" rx="5" ry="3" fill="#FCA5A5" opacity="0.25"/>
          <ellipse cx="90" cy="62" rx="5" ry="3" fill="#FCA5A5" opacity="0.25"/>
        </g>
      </svg>
    </div>
    <!-- ====== ROBOT 2: 金知 (GoldKnow) - 中高 150px ====== -->
    <div
      class="char-slot slot-goldknow"
      tabindex="0"
      role="img"
      aria-label="金知机器人：白色圆润机身，黄色暖光面罩，头顶双丸子天线，胸口书本加星星标志"
    >
      <svg
        width="110" height="170" viewBox="0 0 110 170"
        class="char-svg"
        xmlns="http://www.w3.org/2000/svg"
      >
        <defs>
          <linearGradient id="g-goldknow-body" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#FFFFFF"/>
            <stop offset="100%" stop-color="#FFFBEB"/>
          </linearGradient>
          <linearGradient id="g-goldknow-glass" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stop-color="white" stop-opacity="0.3"/>
            <stop offset="100%" stop-color="white" stop-opacity="0"/>
          </linearGradient>
          <radialGradient id="g-goldknow-face-glow" cx="50%" cy="50%" r="50%">
            <stop offset="0%" stop-color="#FBBF24" stop-opacity="0.2"/>
            <stop offset="100%" stop-color="#FBBF24" stop-opacity="0"/>
          </radialGradient>
          <radialGradient id="g-goldknow-eye-shine" cx="35%" cy="35%" r="60%">
            <stop offset="0%" stop-color="white" stop-opacity="0.8"/>
            <stop offset="100%" stop-color="white" stop-opacity="0"/>
          </radialGradient>
        </defs>
        <!-- Ground shadow -->
        <ellipse cx="55" cy="160" rx="30" ry="5" fill="rgba(0,0,0,0.08)"/>
        <!-- Feet -->
        <ellipse cx="36" cy="154" rx="11" ry="5" fill="#E5E7EB" stroke="#374151" stroke-width="1.1"/>
        <ellipse cx="74" cy="154" rx="11" ry="5" fill="#E5E7EB" stroke="#374151" stroke-width="1.1"/>
        <!-- Legs -->
        <rect x="26" y="140" width="20" height="16" rx="6" fill="url(#g-goldknow-body)" stroke="#374151" stroke-width="1.1"/>
        <rect x="64" y="140" width="20" height="16" rx="6" fill="url(#g-goldknow-body)" stroke="#374151" stroke-width="1.1"/>
        <!-- Arms -->
        <rect x="6" y="78" width="16" height="10" rx="5" fill="url(#g-goldknow-body)" stroke="#374151" stroke-width="1.1"/>
        <rect x="4" y="85" width="10" height="24" rx="5" fill="#E5E7EB" stroke="#374151" stroke-width="0.7"/>
        <rect x="88" y="78" width="16" height="10" rx="5" fill="url(#g-goldknow-body)" stroke="#374151" stroke-width="1.1"/>
        <rect x="96" y="85" width="10" height="24" rx="5" fill="#E5E7EB" stroke="#374151" stroke-width="0.7"/>
        <!-- Body -->
        <rect x="18" y="64" width="74" height="78" rx="15" fill="url(#g-goldknow-body)" stroke="#374151" stroke-width="1.5"/>
        <!-- Chest book+star logo -->
        <g>
          <path d="M 45 92 L 45 112" stroke="#FBBF24" stroke-width="2.5" stroke-linecap="round" fill="none"/>
          <path d="M 65 92 L 65 112" stroke="#FBBF24" stroke-width="2.5" stroke-linecap="round" fill="none"/>
          <path d="M 45 92 Q 55 98 65 92" fill="none" stroke="#FBBF24" stroke-width="2.2" stroke-linecap="round"/>
          <path d="M 45 112 Q 55 106 65 112" fill="none" stroke="#FBBF24" stroke-width="2.2" stroke-linecap="round"/>
          <path d="M 45 92 L 45 112 Q 50 107 55 112 L 55 92 Q 50 97 45 92 Z" fill="#FEF3C7" stroke="#FBBF24" stroke-width="1" opacity="0.6"/>
          <path d="M 55 92 L 55 112 Q 60 107 65 112 L 65 92 Q 60 97 55 92 Z" fill="#FEF3C7" stroke="#FBBF24" stroke-width="1" opacity="0.6"/>
          <path d="M 55 98 L 57 102 L 61 102 L 58 105 L 59 109 L 55 106.5 L 51 109 L 52 105 L 49 102 L 53 102 Z" fill="#FBBF24"/>
        </g>
        <!-- Body glass reflection -->
        <rect x="18" y="64" width="74" height="78" rx="15" fill="url(#g-goldknow-glass)" opacity="0.35"/>
        <!-- Neck -->
        <rect x="42" y="54" width="26" height="14" rx="4" fill="#E5E7EB" stroke="#374151" stroke-width="0.8"/>
        <!-- Head group -->
        <g>
          <!-- Twin antennas -->
          <line x1="36" y1="16" x2="40" y2="26" stroke="#374151" stroke-width="1.5"/>
          <circle cx="34" cy="11" r="6" fill="#FBBF24" stroke="#374151" stroke-width="1.2"/>
          <circle cx="32" cy="9" r="2" fill="white" opacity="0.4"/>
          <line x1="74" y1="16" x2="70" y2="26" stroke="#374151" stroke-width="1.5"/>
          <circle cx="76" cy="11" r="6" fill="#FBBF24" stroke="#374151" stroke-width="1.2"/>
          <circle cx="74" cy="9" r="2" fill="white" opacity="0.4"/>
          <!-- Head -->
          <rect x="22" y="24" width="66" height="40" rx="12" fill="url(#g-goldknow-body)" stroke="#374151" stroke-width="1.5"/>
          <!-- Face screen -->
          <rect x="28" y="29" width="54" height="30" rx="7" fill="#FEF9C3" stroke="#FBBF24" stroke-opacity="0.3" stroke-width="0.8"/>
          <rect x="28" y="29" width="54" height="30" rx="7" fill="url(#g-goldknow-face-glow)"/>
          <rect x="28" y="29" width="54" height="30" rx="7" fill="url(#g-goldknow-glass)" opacity="0.2"/>
          <!-- Left eye -->
          <circle cx="44" cy="45" r="6" fill="#8B6914" opacity="0.3"/>
          <circle cx="44" cy="45" r="5" fill="#FBBF24"/>
          <circle cx="44" cy="45" r="3" fill="#1C1917"/>
          <circle cx="43" cy="44" r="1.2" fill="url(#g-goldknow-eye-shine)"/>
          <!-- Right eye -->
          <circle cx="66" cy="45" r="6" fill="#8B6914" opacity="0.3"/>
          <circle cx="66" cy="45" r="5" fill="#FBBF24"/>
          <circle cx="66" cy="45" r="3" fill="#1C1917"/>
          <circle cx="65" cy="44" r="1.2" fill="url(#g-goldknow-eye-shine)"/>
          <!-- Eyebrows -->
          <path d="M 37 37 Q 44 34 50 37" fill="none" stroke="#374151" stroke-width="1.2" stroke-linecap="round"/>
          <path d="M 60 37 Q 66 34 73 37" fill="none" stroke="#374151" stroke-width="1.2" stroke-linecap="round"/>
          <!-- O-mouth -->
          <path d="M 50 52 Q 55 58 60 52" fill="none" stroke="#374151" stroke-width="1.8" stroke-linecap="round"/>
          <!-- Cheek blush -->
          <ellipse cx="30" cy="53" rx="4" ry="2.5" fill="#FCA5A5" opacity="0.2"/>
          <ellipse cx="80" cy="53" rx="4" ry="2.5" fill="#FCA5A5" opacity="0.2"/>
        </g>
      </svg>
    </div>
    <!-- ====== ROBOT 3: 深潜 (DeepDive) - 中等 120px ====== -->
    <div
      class="char-slot slot-deepdive"
      tabindex="0"
      role="img"
      aria-label="深潜机器人：深蓝色宇航员头盔，紫色发光面罩，胸口雪花放射标志"
    >
      <svg
        width="100" height="140" viewBox="0 0 100 140"
        class="char-svg"
        xmlns="http://www.w3.org/2000/svg"
      >
        <defs>
          <linearGradient id="g-deepdive-body" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#1E3A5F"/>
            <stop offset="100%" stop-color="#15294A"/>
          </linearGradient>
          <radialGradient id="g-deepdive-face-glow" cx="50%" cy="50%" r="50%">
            <stop offset="0%" stop-color="#7C3AED" stop-opacity="0.25"/>
            <stop offset="100%" stop-color="#7C3AED" stop-opacity="0"/>
          </radialGradient>
          <linearGradient id="g-deepdive-glass" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stop-color="white" stop-opacity="0.15"/>
            <stop offset="100%" stop-color="white" stop-opacity="0"/>
          </linearGradient>
          <radialGradient id="g-deepdive-eye-shine" cx="35%" cy="35%" r="60%">
            <stop offset="0%" stop-color="white" stop-opacity="0.9"/>
            <stop offset="100%" stop-color="white" stop-opacity="0"/>
          </radialGradient>
        </defs>
        <!-- Ground shadow -->
        <ellipse cx="50" cy="132" rx="28" ry="5" fill="rgba(0,0,0,0.09)"/>
        <!-- Feet -->
        <ellipse cx="34" cy="126" rx="10" ry="4.5" fill="#15294A" stroke="#2D5A87" stroke-width="1"/>
        <ellipse cx="66" cy="126" rx="10" ry="4.5" fill="#15294A" stroke="#2D5A87" stroke-width="1"/>
        <!-- Legs -->
        <rect x="24" y="112" width="20" height="16" rx="5" fill="url(#g-deepdive-body)" stroke="#2D5A87" stroke-width="1"/>
        <rect x="56" y="112" width="20" height="16" rx="5" fill="url(#g-deepdive-body)" stroke="#2D5A87" stroke-width="1"/>
        <!-- Arms -->
        <rect x="4" y="62" width="16" height="10" rx="5" fill="url(#g-deepdive-body)" stroke="#2D5A87" stroke-width="1"/>
        <rect x="2" y="69" width="10" height="22" rx="5" fill="#15294A" stroke="#2D5A87" stroke-width="0.7"/>
        <rect x="80" y="62" width="16" height="10" rx="5" fill="url(#g-deepdive-body)" stroke="#2D5A87" stroke-width="1"/>
        <rect x="88" y="69" width="10" height="22" rx="5" fill="#15294A" stroke="#2D5A87" stroke-width="0.7"/>
        <!-- Body -->
        <rect x="16" y="50" width="68" height="64" rx="14" fill="url(#g-deepdive-body)" stroke="#2D5A87" stroke-width="1.2"/>
        <!-- Chest snowflake logo -->
        <g>
          <circle cx="50" cy="82" r="8" fill="#7C3AED" opacity="0.15"/>
          <path d="M 50 70 L 50 94 M 36 82 L 64 82 M 40 74 L 60 90 M 40 90 L 60 74"
                stroke="#7C3AED" stroke-width="2" stroke-linecap="round" opacity="0.9"/>
          <circle cx="50" cy="82" r="3" fill="none" stroke="#A78BFA" stroke-width="1.5"/>
          <circle cx="50" cy="82" r="1.5" fill="#A78BFA"/>
        </g>
        <!-- Body glass highlight -->
        <rect x="16" y="50" width="68" height="64" rx="14" fill="url(#g-deepdive-glass)" opacity="0.2"/>
        <!-- Neck -->
        <rect x="38" y="42" width="24" height="12" rx="3" fill="#15294A" stroke="#2D5A87" stroke-width="0.7"/>
        <!-- Head group -->
        <g>
          <!-- Earmuffs -->
          <rect x="4" y="18" width="14" height="26" rx="7" fill="#15294A" stroke="#2D5A87" stroke-width="1.2"/>
          <circle cx="11" cy="31" r="4" fill="#2D5A87" opacity="0.5"/>
          <rect x="82" y="18" width="14" height="26" rx="7" fill="#15294A" stroke="#2D5A87" stroke-width="1.2"/>
          <circle cx="89" cy="31" r="4" fill="#2D5A87" opacity="0.5"/>
          <!-- Helmet white stripe -->
          <rect x="22" y="10" width="56" height="6" rx="3" fill="white" opacity="0.85"/>
          <!-- Helmet -->
          <rect x="18" y="14" width="64" height="36" rx="14" fill="url(#g-deepdive-body)" stroke="#2D5A87" stroke-width="1.2"/>
          <!-- Face screen -->
          <rect x="24" y="18" width="52" height="28" rx="7" fill="#2D1B69" stroke="#7C3AED" stroke-opacity="0.4" stroke-width="0.8"/>
          <rect x="24" y="18" width="52" height="28" rx="7" fill="url(#g-deepdive-face-glow)"/>
          <rect x="24" y="18" width="52" height="28" rx="7" fill="url(#g-deepdive-glass)" opacity="0.15"/>
          <!-- Left eye -->
          <circle cx="40" cy="33" r="6" fill="#7C3AED" opacity="0.4"/>
          <circle cx="40" cy="33" r="5" fill="#7C3AED" stroke="#A78BFA" stroke-width="0.8"/>
          <circle cx="40" cy="33" r="3" fill="#DDD6FE"/>
          <circle cx="39" cy="32" r="1.5" fill="url(#g-deepdive-eye-shine)"/>
          <!-- Right eye -->
          <circle cx="60" cy="33" r="6" fill="#7C3AED" opacity="0.4"/>
          <circle cx="60" cy="33" r="5" fill="#7C3AED" stroke="#A78BFA" stroke-width="0.8"/>
          <circle cx="60" cy="33" r="3" fill="#DDD6FE"/>
          <circle cx="59" cy="32" r="1.5" fill="url(#g-deepdive-eye-shine)"/>
          <!-- Smile mouth -->
          <path d="M 42 42 Q 50 50 58 42" fill="none" stroke="#A78BFA" stroke-width="1.5" stroke-linecap="round"/>
        </g>
      </svg>
    </div>
    <!-- ====== ROBOT 4: 闪灵 (Flash) - 最矮 80px ====== -->
    <div
      class="char-slot slot-flash"
      tabindex="0"
      role="img"
      aria-label="闪灵机器人：白色圆润机身，青色发光面罩，胸口闪电标志，最Q最萌"
    >
      <svg
        width="70" height="100" viewBox="0 0 70 100"
        class="char-svg"
        xmlns="http://www.w3.org/2000/svg"
      >
        <defs>
          <linearGradient id="g-flash-body" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#FFFFFF"/>
            <stop offset="100%" stop-color="#F0FDFA"/>
          </linearGradient>
          <radialGradient id="g-flash-face-glow" cx="50%" cy="50%" r="50%">
            <stop offset="0%" stop-color="#06B6D4" stop-opacity="0.2"/>
            <stop offset="100%" stop-color="#06B6D4" stop-opacity="0"/>
          </radialGradient>
          <linearGradient id="g-flash-glass" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stop-color="white" stop-opacity="0.35"/>
            <stop offset="100%" stop-color="white" stop-opacity="0"/>
          </linearGradient>
          <radialGradient id="g-flash-eye-shine" cx="35%" cy="35%" r="60%">
            <stop offset="0%" stop-color="white" stop-opacity="0.9"/>
            <stop offset="100%" stop-color="white" stop-opacity="0"/>
          </radialGradient>
        </defs>
        <!-- Ground shadow -->
        <ellipse cx="35" cy="92" rx="22" ry="4" fill="rgba(0,0,0,0.07)"/>
        <!-- Feet -->
        <ellipse cx="24" cy="86" rx="8" ry="4" fill="#E5E7EB" stroke="#374151" stroke-width="0.9"/>
        <ellipse cx="46" cy="86" rx="8" ry="4" fill="#E5E7EB" stroke="#374151" stroke-width="0.9"/>
        <!-- Legs -->
        <rect x="16" y="76" width="16" height="12" rx="4" fill="url(#g-flash-body)" stroke="#374151" stroke-width="0.9"/>
        <rect x="38" y="76" width="16" height="12" rx="4" fill="url(#g-flash-body)" stroke="#374151" stroke-width="0.9"/>
        <!-- Arms -->
        <rect x="3" y="44" width="14" height="9" rx="4.5" fill="url(#g-flash-body)" stroke="#374151" stroke-width="0.9"/>
        <rect x="53" y="44" width="14" height="9" rx="4.5" fill="url(#g-flash-body)" stroke="#374151" stroke-width="0.9"/>
        <!-- Body -->
        <rect x="11" y="38" width="48" height="40" rx="12" fill="url(#g-flash-body)" stroke="#374151" stroke-width="1.2"/>
        <!-- Chest lightning -->
        <g>
          <path d="M 33 42 L 28 53 L 34 53 L 30 64 L 40 50 L 34 50 Z" fill="#06B6D4" opacity="0.85"/>
        </g>
        <!-- Body glass shine -->
        <rect x="11" y="38" width="48" height="40" rx="12" fill="url(#g-flash-glass)" opacity="0.35"/>
        <!-- Neck -->
        <rect x="27" y="30" width="16" height="10" rx="3" fill="#E5E7EB" stroke="#374151" stroke-width="0.7"/>
        <!-- Head group -->
        <g>
          <rect x="12" y="10" width="46" height="28" rx="10" fill="url(#g-flash-body)" stroke="#374151" stroke-width="1.2"/>
          <rect x="16" y="14" width="38" height="20" rx="5" fill="#ECFEFF" stroke="#06B6D4" stroke-opacity="0.3" stroke-width="0.8"/>
          <rect x="16" y="14" width="38" height="20" rx="5" fill="url(#g-flash-face-glow)"/>
          <rect x="16" y="14" width="38" height="20" rx="5" fill="url(#g-flash-glass)" opacity="0.2"/>
          <!-- Left star eye -->
          <g>
            <path d="M 27 22 L 29 18 L 31 22 L 35 22 L 32 25 L 33 29 L 29 26.5 L 25 29 L 26 25 L 23 22 Z" fill="#06B6D4"/>
            <circle cx="29" cy="23" r="1.5" fill="white" opacity="0.7"/>
          </g>
          <!-- Right star eye -->
          <g>
            <path d="M 38 22 L 40 18 L 42 22 L 46 22 L 43 25 L 44 29 L 40 26.5 L 36 29 L 37 25 L 34 22 Z" fill="#06B6D4"/>
            <circle cx="40" cy="23" r="1.5" fill="white" opacity="0.7"/>
          </g>
          <!-- O-mouth -->
          <path d="M 30 30 Q 35 36 40 30" fill="none" stroke="#374151" stroke-width="1.5" stroke-linecap="round"/>
          <!-- Cheek blush -->
          <ellipse cx="18" cy="26" rx="3.5" ry="2" fill="#FCA5A5" opacity="0.2"/>
          <ellipse cx="52" cy="26" rx="3.5" ry="2" fill="#FCA5A5" opacity="0.2"/>
        </g>
      </svg>
    </div>
  </div>
</template>
