<template>
  <div id="app">
    <el-menu mode="horizontal" router>
      <el-menu-item index="/">儀表板</el-menu-item>
      <el-menu-item index="/tasks">待辦清單</el-menu-item>
      <el-menu-item index="/my-applications">我的申請</el-menu-item>
      <el-menu-item index="/audit-log">稽核 Log</el-menu-item>
    </el-menu>
    <router-view />
  </div>
</template>
