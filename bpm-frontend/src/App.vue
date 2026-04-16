<template>
  <div id="app">
    <nav>
      <router-link to="/">首頁</router-link>
      <router-link to="/audit-log" style="margin-left: 16px">稽核 Log</router-link>
    </nav>
    <router-view />
  </div>
</template>
