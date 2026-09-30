<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { listCampuses, listSchools, type CampusItem, type SchoolItem } from "@/api/catalog";
import {
  createCampus,
  createCourse,
  createSchool,
  listCourses,
  listSchoolProvinces,
  updateCampus,
  updateCourse,
  updateSchool,
  type CourseItem
} from "@/api/adminCatalog";
import type { ProvinceItem } from "@/api/catalog";
import StatusTag from "@/components/StatusTag.vue";

const tab = ref("schools");
const schools = ref<SchoolItem[]>([]);
const campuses = ref<CampusItem[]>([]);
const courses = ref<CourseItem[]>([]);
const provinces = ref<ProvinceItem[]>([]);
const schoolId = ref<number | undefined>();
const selectedSchool = ref<SchoolItem | null>(null);
const filter = reactive({ keyword: "", provinceId: undefined as number | undefined });
const schoolForm = reactive({ visible: false, name: "", provinceId: 15 as number | undefined });
const campusForm = reactive({ visible: false, name: "" });
const courseForm = reactive({ visible: false, courseCode: "", courseName: "" });

async function loadProvinces() {
  provinces.value = (await listSchoolProvinces()) || [];
}

async function loadSchools() {
  schools.value =
    (
      await listSchools({
        keyword: filter.keyword.trim() || undefined,
        provinceId: filter.provinceId || undefined,
        size: 500
      })
    ).list || [];
  if (!schoolId.value && schools.value.length) {
    schoolId.value = schools.value[0].id;
  }
  if (!schools.value.some((item) => item.id === schoolId.value)) {
    schoolId.value = schools.value[0]?.id;
  }
  selectedSchool.value = schools.value.find((item) => item.id === schoolId.value) || schools.value[0] || null;
}

async function onSchoolFilter() {
  await loadSchools();
  await loadChildren();
}

async function loadChildren() {
  if (!schoolId.value) {
    campuses.value = [];
    courses.value = [];
    return;
  }
  campuses.value = (await listCampuses(schoolId.value, { size: 200 })).list || [];
  courses.value = (await listCourses(schoolId.value, { size: 200 })).list || [];
}

async function selectSchool(row: SchoolItem) {
  schoolId.value = row.id;
  selectedSchool.value = row;
  await loadChildren();
}

async function onSchoolSelect(id: number) {
  const row = schools.value.find((item) => item.id === id);
  if (row) {
    await selectSchool(row);
  }
}

async function submitSchool() {
  if (!schoolForm.provinceId) {
    ElMessage.warning("请选择省份");
    return;
  }
  await createSchool(schoolForm.name.trim(), schoolForm.provinceId);
  ElMessage.success("已添加学校");
  schoolForm.visible = false;
  schoolForm.name = "";
  schoolForm.provinceId = 15;
  await loadProvinces();
  await loadSchools();
  await loadChildren();
}

async function toggleSchool(row: SchoolItem) {
  await updateSchool(row.id, { status: row.status === 1 ? 0 : 1 });
  ElMessage.success("已更新");
  await loadSchools();
}

async function submitCampus() {
  if (!schoolId.value) return;
  await createCampus(schoolId.value, campusForm.name.trim());
  ElMessage.success("已添加校区");
  campusForm.visible = false;
  campusForm.name = "";
  await loadChildren();
}

async function toggleCampus(row: CampusItem) {
  await updateCampus(row.id, { status: row.status === 1 ? 0 : 1 });
  ElMessage.success("已更新");
  await loadChildren();
}

async function submitCourse() {
  if (!schoolId.value) return;
  await createCourse(schoolId.value, courseForm.courseCode.trim(), courseForm.courseName.trim());
  ElMessage.success("已添加课程");
  courseForm.visible = false;
  courseForm.courseCode = "";
  courseForm.courseName = "";
  await loadChildren();
}

async function toggleCourse(row: CourseItem) {
  await updateCourse(row.id, { status: row.status === 1 ? 0 : 1 });
  ElMessage.success("已更新");
  await loadChildren();
}

onMounted(async () => {
  await loadProvinces();
  await loadSchools();
  await loadChildren();
});
</script>

<template>
  <div class="ka-page">
    <section class="ka-card">
      <el-tabs v-model="tab" class="catalog-tabs">
        <el-tab-pane label="学校管理" name="schools">
          <div class="ka-split catalog">
            <div class="school-pane">
              <div class="ka-toolbar">
                <el-input
                  v-model="filter.keyword"
                  placeholder="搜索学校"
                  clearable
                  style="width: 180px"
                  @keyup.enter="onSchoolFilter"
                  @clear="onSchoolFilter"
                />
                <el-select
                  v-model="filter.provinceId"
                  placeholder="全部省份"
                  clearable
                  style="width: 140px"
                  @change="onSchoolFilter"
                >
                  <el-option v-for="item in provinces" :key="item.id" :label="item.name" :value="item.id" />
                </el-select>
                <el-button @click="onSchoolFilter">查询</el-button>
                <el-button type="primary" @click="schoolForm.visible = true">新增学校</el-button>
              </div>
              <el-table :data="schools" highlight-current-row class="mt school-table" max-height="calc(100vh - 250px)" @row-click="selectSchool">
                <el-table-column prop="id" label="学校ID" width="90" />
                <el-table-column prop="name" label="学校名称" />
                <el-table-column prop="provinceName" label="省份" width="90" />
                <el-table-column label="状态" width="90">
                  <template #default="{ row }">
                    <StatusTag :text="row.status === 1 ? '启用' : '停用'" :tone="row.status === 1 ? 'success' : 'muted'" />
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="90">
                  <template #default="{ row }">
                    <button class="link-btn" @click.stop="toggleSchool(row)">{{ row.status === 1 ? "停用" : "启用" }}</button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
            <aside class="side campus-panel">
              <h3>{{ selectedSchool?.name || "校区详情" }}</h3>
              <p class="hint">当前学校下的校区，可直接启用或停用</p>
              <ul>
                <li v-for="item in campuses" :key="item.id">
                  <span>{{ item.name }}</span>
                  <span class="acts">
                    <StatusTag :text="item.status === 1 ? '启用' : '停用'" :tone="item.status === 1 ? 'success' : 'muted'" />
                    <button class="link-btn" @click="toggleCampus(item)">{{ item.status === 1 ? "停用" : "启用" }}</button>
                  </span>
                </li>
                <li v-if="!campuses.length" class="empty">暂无校区</li>
              </ul>
              <el-button class="add-campus" size="small" type="primary" :disabled="!schoolId" @click="campusForm.visible = true">新增校区</el-button>
            </aside>
          </div>
        </el-tab-pane>
        <el-tab-pane label="校区管理" name="campuses">
          <div class="ka-toolbar">
            <el-select v-model="schoolId" placeholder="选择学校" style="width: 240px" @change="onSchoolSelect">
              <el-option v-for="item in schools" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
            <el-button type="primary" :disabled="!schoolId" @click="campusForm.visible = true">新增校区</el-button>
          </div>
          <el-table :data="campuses" class="mt">
            <el-table-column prop="id" label="校区ID" width="90" />
            <el-table-column prop="name" label="校区名称" />
            <el-table-column prop="schoolName" label="所属学校" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <StatusTag :text="row.status === 1 ? '启用' : '停用'" :tone="row.status === 1 ? 'success' : 'muted'" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <button class="link-btn" @click="toggleCampus(row)">{{ row.status === 1 ? "停用" : "启用" }}</button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="课程管理" name="courses">
          <div class="ka-toolbar">
            <el-select v-model="schoolId" placeholder="选择学校" style="width: 240px" @change="loadChildren">
              <el-option v-for="item in schools" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
            <el-button type="primary" :disabled="!schoolId" @click="courseForm.visible = true">新增课程</el-button>
          </div>
          <el-table :data="courses" class="mt">
            <el-table-column prop="courseCode" label="课程代码" width="140" />
            <el-table-column prop="courseName" label="课程名称" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <StatusTag :text="row.status === 1 ? '启用' : '停用'" :tone="row.status === 1 ? 'success' : 'muted'" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <button class="link-btn" @click="toggleCourse(row)">{{ row.status === 1 ? "停用" : "启用" }}</button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </section>
  </div>

  <el-dialog v-model="schoolForm.visible" title="新增学校" width="400px">
    <el-form label-width="72px">
      <el-form-item label="名称"><el-input v-model="schoolForm.name" placeholder="学校名称" /></el-form-item>
      <el-form-item label="省份">
        <el-select v-model="schoolForm.provinceId" placeholder="请选择省份" style="width: 100%">
          <el-option v-for="item in provinces" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="schoolForm.visible = false">取消</el-button>
      <el-button type="primary" :disabled="!schoolForm.name.trim()" @click="submitSchool">确定</el-button>
    </template>
  </el-dialog>
  <el-dialog v-model="campusForm.visible" title="新增校区" width="400px">
    <el-input v-model="campusForm.name" placeholder="校区名称" />
    <template #footer>
      <el-button @click="campusForm.visible = false">取消</el-button>
      <el-button type="primary" :disabled="!campusForm.name.trim()" @click="submitCampus">确定</el-button>
    </template>
  </el-dialog>
  <el-dialog v-model="courseForm.visible" title="新增课程" width="400px">
    <el-form label-width="80px">
      <el-form-item label="代码"><el-input v-model="courseForm.courseCode" /></el-form-item>
      <el-form-item label="名称"><el-input v-model="courseForm.courseName" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="courseForm.visible = false">取消</el-button>
      <el-button type="primary" :disabled="!courseForm.courseCode.trim() || !courseForm.courseName.trim()" @click="submitCourse">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.ka-card {
  overflow: visible;
}
.catalog-tabs {
  overflow: visible;
}
.catalog-tabs :deep(.el-tabs__content),
.catalog-tabs :deep(.el-tab-pane) {
  overflow: visible;
}
.school-pane {
  min-width: 0;
}
.campus-panel {
  position: sticky;
  top: 16px;
  align-self: start;
  z-index: 8;
  max-height: calc(100vh - 96px);
  overflow: auto;
  background: #fff;
  border: 1px solid var(--ka-border);
  border-radius: 12px;
  padding: 14px 16px 16px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.06);
}
.mt {
  margin-top: 12px;
}
.side h3 {
  margin: 0;
  font-size: 16px;
}
.hint {
  color: var(--ka-muted);
  font-size: 12px;
  margin: 6px 0 12px;
}
.side ul {
  list-style: none;
  margin: 0;
  padding: 0;
}
.side li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid var(--ka-border);
  font-size: 13px;
}
.empty {
  color: var(--ka-muted);
  border: none;
}
.acts {
  display: flex;
  align-items: center;
  gap: 8px;
}
.add-campus {
  margin-top: 12px;
}
</style>
