# Agent Trip Planner 前端 API 接口文档

> **版本**: v1.0  
> **基础地址**: `http://localhost:8080` (通过 Gateway 网关)  
> **最后更新**: 2026-09-17

---

## 目录

1. [通用约定](#1-通用约定)
2. [认证服务 Auth (端口 8081)](#2-认证服务-auth)
3. [行程服务 Trip (端口 8082)](#3-行程服务-trip)
4. [规划服务 Plan (端口 8083)](#4-规划服务-plan)
5. [通知服务 Notification (端口 8085)](#5-通知服务-notification)
6. [WebSocket 接入](#6-websocket-接入)
7. [前端典型使用流程](#7-前端典型使用流程)
8. [TypeScript 类型参考](#8-typescript-类型参考)

---

## 1. 通用约定

### 1.1 统一响应格式

所有接口统一使用 `ApiResponse` 封装：

```json
{
  "code": 200,
  "message": "success",
  "data": { ... },
  "timestamp": 1726550400000
}
```

**错误响应示例：**

```json
{
  "code": 40001,
  "message": "参数校验失败",
  "data": null,
  "timestamp": 1726550400000,
  "errors": [
    { "field": "email", "message": "邮箱格式不正确" }
  ]
}
```

### 1.2 认证方式

采用 JWT (JSON Web Token) 认证，在请求头中携带 Bearer Token：

```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

Token 生命周期：
- **Access Token**: 有效期 30 分钟
- **Refresh Token**: 有效期 7 天

### 1.3 分页参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| page | number | 否 | 页码，默认 1 |
| size | number | 否 | 每页条数，默认 20，最大 100 |
| sort | string | 否 | 排序字段，如 `createdAt,desc` |

**分页响应格式：**

```json
{
  "content": [],
  "totalElements": 100,
  "totalPages": 5,
  "number": 0,
  "size": 20
}
```

### 1.4 错误码表

| 错误码 | 说明 |
|--------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | 未认证/Token过期 |
| 403 | 无权限访问 |
| 404 | 资源不存在 |
| 409 | 资源冲突 |
| 429 | 请求过于频繁 |
| 500 | 服务器内部错误 |
| 10001 | 用户不存在 |
| 10002 | 密码错误 |
| 10003 | 用户已被禁用 |
| 10004 | 邮箱已注册 |
| 20001 | 行程不存在 |
| 20002 | 无权操作此行程 |
| 20003 | 行程状态不允许此操作 |
| 30001 | 规划任务不存在 |
| 30002 | 规划正在进行中 |
| 30003 | 解析输入失败 |
| 40001 | 通知不存在 |

---

## 2. 认证服务 Auth

> **服务端口**: 8081  
> **基础路径**: `/auth`

---

### 2.1 用户注册

**POST** `/auth/register`

**请求体：**

```json
{
  "username": "zhangsan",
  "email": "zhangsan@example.com",
  "password": "Abc@1234",
  "nickname": "张三"
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "注册成功",
  "data": {
    "userId": "usr_a1b2c3d4e5f6",
    "username": "zhangsan",
    "email": "zhangsan@example.com",
    "nickname": "张三",
    "createdAt": "2026-09-17T10:30:00Z"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface RegisterRequest {
  username: string;
  email: string;
  password: string; // 8-72字符，必须含大小写字母+数字+特殊字符
  nickname?: string;
}

interface RegisterResponse {
  userId: string;
  username: string;
  email: string;
  nickname: string;
  createdAt: string;
}
```

---

### 2.2 用户登录

**POST** `/auth/login`

**请求体：**

```json
{
  "email": "zhangsan@example.com",
  "password": "Abc@1234"
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "dGhpcyBpcyBhIHJlZnJlc2ggdG9rZW4...",
    "expiresIn": 1800,
    "tokenType": "Bearer",
    "user": {
      "userId": "usr_a1b2c3d4e5f6",
      "username": "zhangsan",
      "email": "zhangsan@example.com",
      "nickname": "张三",
      "avatar": null
    }
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface LoginRequest {
  email: string;
  password: string;
}

interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number; // 秒
  tokenType: string;
  user: UserInfo;
}

interface UserInfo {
  userId: string;
  username: string;
  email: string;
  nickname: string;
  avatar: string | null;
}
```

---

### 2.3 刷新 Token

**POST** `/auth/refresh`

**请求体：**

```json
{
  "refreshToken": "dGhpcyBpcyBhIHJlZnJlc2ggdG9rZW4..."
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "刷新成功",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "bmV3IHJlZnJlc2ggdG9rZW4...",
    "expiresIn": 1800,
    "tokenType": "Bearer"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface RefreshTokenRequest {
  refreshToken: string;
}

interface RefreshTokenResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  tokenType: string;
}
```

---

### 2.4 用户登出

**POST** `/auth/logout`

**请求头：** `Authorization: Bearer <accessToken>`

**请求体：** 无

**响应示例：**

```json
{
  "code": 200,
  "message": "登出成功",
  "data": null,
  "timestamp": 1726550400000
}
```

---

### 2.5 获取当前用户信息

**GET** `/auth/me`

**请求头：** `Authorization: Bearer <accessToken>`

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "userId": "usr_a1b2c3d4e5f6",
    "username": "zhangsan",
    "email": "zhangsan@example.com",
    "nickname": "张三",
    "avatar": "https://cdn.example.com/avatars/usr_a1b2c3d4e5f6.jpg",
    "phone": "138****8888",
    "createdAt": "2026-09-17T10:30:00Z",
    "updatedAt": "2026-09-17T12:00:00Z"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface UserProfile {
  userId: string;
  username: string;
  email: string;
  nickname: string;
  avatar: string | null;
  phone: string | null;
  createdAt: string;
  updatedAt: string;
}
```

---

### 2.6 更新个人信息

**PUT** `/auth/me`

**请求头：** `Authorization: Bearer <accessToken>`

**请求体：**

```json
{
  "nickname": "三哥",
  "phone": "13812345678",
  "avatar": "https://cdn.example.com/new-avatar.jpg"
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "更新成功",
  "data": {
    "userId": "usr_a1b2c3d4e5f6",
    "username": "zhangsan",
    "email": "zhangsan@example.com",
    "nickname": "三哥",
    "avatar": "https://cdn.example.com/new-avatar.jpg",
    "phone": "13812345678",
    "createdAt": "2026-09-17T10:30:00Z",
    "updatedAt": "2026-09-17T14:30:00Z"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface UpdateProfileRequest {
  nickname?: string;
  phone?: string;
  avatar?: string;
}
```

---

### 2.7 修改密码

**PUT** `/auth/password`

**请求头：** `Authorization: Bearer <accessToken>`

**请求体：**

```json
{
  "oldPassword": "Abc@1234",
  "newPassword": "Def@5678"
}
```

**密码规则**: 8-72字符，必须包含大写字母、小写字母、数字和特殊字符

**响应示例：**

```json
{
  "code": 200,
  "message": "密码修改成功，请重新登录",
  "data": null,
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface ChangePasswordRequest {
  oldPassword: string;
  newPassword: string; // 8-72字符，含大小写字母+数字+特殊字符
}
```

---

### 2.8 获取用户偏好

**GET** `/auth/preferences`

**请求头：** `Authorization: Bearer <accessToken>`

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "transportMode": "mixed",
    "intensity": "standard",
    "budget": "medium",
    "language": "zh-CN",
    "currency": "CNY",
    "notifications": {
      "email": true,
      "push": true,
      "sms": false
    },
    "defaultCity": "上海"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface UserPreferences {
  transportMode: TransportMode;
  intensity: IntensityLevel;
  budget: 'low' | 'medium' | 'high';
  language: string;
  currency: string;
  notifications: NotificationSettings;
  defaultCity: string;
}

type TransportMode = 'walk' | 'transit' | 'drive' | 'bike' | 'mixed';
type IntensityLevel = 'relaxed' | 'standard' | 'packed';

interface NotificationSettings {
  email: boolean;
  push: boolean;
  sms: boolean;
}
```

---

### 2.9 更新用户偏好 (PUT)

**PUT** `/auth/preferences`

**请求头：** `Authorization: Bearer <accessToken>`

**请求体（完整替换）：**

```json
{
  "transportMode": "transit",
  "intensity": "relaxed",
  "budget": "high",
  "language": "zh-CN",
  "currency": "CNY",
  "notifications": {
    "email": true,
    "push": false,
    "sms": false
  },
  "defaultCity": "北京"
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "偏好更新成功",
  "data": {
    "transportMode": "transit",
    "intensity": "relaxed",
    "budget": "high",
    "language": "zh-CN",
    "currency": "CNY",
    "notifications": {
      "email": true,
      "push": false,
      "sms": false
    },
    "defaultCity": "北京"
  },
  "timestamp": 1726550400000
}
```

---

### 2.10 更新用户偏好 (PATCH)

**PATCH** `/auth/preferences`

**请求头：** `Authorization: Bearer <accessToken>`

**请求体（部分更新）：**

```json
{
  "transportMode": "bike"
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "偏好更新成功",
  "data": {
    "transportMode": "bike",
    "intensity": "relaxed",
    "budget": "high",
    "language": "zh-CN",
    "currency": "CNY",
    "notifications": {
      "email": true,
      "push": false,
      "sms": false
    },
    "defaultCity": "北京"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface UpdatePreferencesRequest {
  transportMode?: TransportMode;
  intensity?: IntensityLevel;
  budget?: 'low' | 'medium' | 'high';
  language?: string;
  currency?: string;
  notifications?: Partial<NotificationSettings>;
  defaultCity?: string;
}
```

---

## 3. 行程服务 Trip

> **服务端口**: 8082  
> **基础路径**: `/trips`

---

### 3.1 创建行程

**POST** `/trips`

**请求头：** `Authorization: Bearer <accessToken>`

**请求体：**

```json
{
  "title": "2026年国庆日本关西之旅",
  "description": "京都、大阪、奈良 7 天深度游",
  "destination": "日本关西",
  "startDate": "2026-10-01",
  "endDate": "2026-10-07",
  "budget": 15000,
  "currency": "CNY",
  "travelers": 2,
  "tags": ["日本", "关西", "文化", "美食"],
  "coverImage": null
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "行程创建成功",
  "data": {
    "tripId": "trip_x1y2z3a4b5c6",
    "title": "2026年国庆日本关西之旅",
    "description": "京都、大阪、奈良 7 天深度游",
    "destination": "日本关西",
    "startDate": "2026-10-01",
    "endDate": "2026-10-07",
    "budget": 15000,
    "currency": "CNY",
    "travelers": 2,
    "tags": ["日本", "关西", "文化", "美食"],
    "coverImage": null,
    "status": "DRAFT",
    "ownerId": "usr_a1b2c3d4e5f6",
    "createdAt": "2026-09-17T10:30:00Z",
    "updatedAt": "2026-09-17T10:30:00Z"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface CreateTripRequest {
  title: string;
  description?: string;
  destination: string;
  startDate: string; // YYYY-MM-DD
  endDate: string;   // YYYY-MM-DD
  budget?: number;
  currency?: string;
  travelers?: number;
  tags?: string[];
  coverImage?: string | null;
}

type TripStatus = 'DRAFT' | 'PLANNING' | 'PLANNED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

interface Trip {
  tripId: string;
  title: string;
  description: string | null;
  destination: string;
  startDate: string;
  endDate: string;
  budget: number | null;
  currency: string;
  travelers: number;
  tags: string[];
  coverImage: string | null;
  status: TripStatus;
  ownerId: string;
  createdAt: string;
  updatedAt: string;
}
```

---

### 3.2 查询行程列表

**GET** `/trips`

**请求头：** `Authorization: Bearer <accessToken>`

**查询参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| page | number | 否 | 页码，默认 1 |
| size | number | 否 | 每页条数，默认 20 |
| status | string | 否 | 按状态筛选 |
| keyword | string | 否 | 搜索关键词（标题/描述） |
| tag | string | 否 | 按标签筛选 |
| startDateFrom | string | 否 | 开始日期下限 |
| startDateTo | string | 否 | 开始日期上限 |
| sort | string | 否 | 排序，默认 `createdAt,desc` |

**请求示例：**

```
GET /trips?page=1&size=10&status=PLANNED&keyword=日本
```

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "content": [
      {
        "tripId": "trip_x1y2z3a4b5c6",
        "title": "2026年国庆日本关西之旅",
        "destination": "日本关西",
        "startDate": "2026-10-01",
        "endDate": "2026-10-07",
        "status": "PLANNED",
        "coverImage": "https://cdn.example.com/trips/trip_x1y2z3a4b5c6.jpg",
        "daysCount": 7,
        "createdAt": "2026-09-17T10:30:00Z"
      }
    ],
    "totalElements": 1,
    "totalPages": 1,
    "number": 0,
    "size": 10
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface TripListQuery {
  page?: number;
  size?: number;
  status?: TripStatus;
  keyword?: string;
  tag?: string;
  startDateFrom?: string;
  startDateTo?: string;
  sort?: string;
}

interface TripSummary {
  tripId: string;
  title: string;
  destination: string;
  startDate: string;
  endDate: string;
  status: TripStatus;
  coverImage: string | null;
  daysCount: number;
  createdAt: string;
}
```

---

### 3.3 获取行程详情

**GET** `/trips/{tripId}`

**请求头：** `Authorization: Bearer <accessToken>`

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "tripId": "trip_x1y2z3a4b5c6",
    "title": "2026年国庆日本关西之旅",
    "description": "京都、大阪、奈良 7 天深度游",
    "destination": "日本关西",
    "startDate": "2026-10-01",
    "endDate": "2026-10-07",
    "budget": 15000,
    "currency": "CNY",
    "travelers": 2,
    "tags": ["日本", "关西", "文化", "美食"],
    "coverImage": "https://cdn.example.com/trips/trip_x1y2z3a4b5c6.jpg",
    "status": "PLANNED",
    "ownerId": "usr_a1b2c3d4e5f6",
    "days": [
      {
        "dayNumber": 1,
        "date": "2026-10-01",
        "title": "抵达大阪",
        "location": "大阪",
        "items": [
          {
            "itemId": "item_a1b2c3",
            "order": 1,
            "title": "关西国际机场",
            "type": "attraction",
            "location": {
              "lat": 34.4347,
              "lng": 135.2441,
              "address": "大阪府泉南郡田尻町"
            },
            "startTime": "14:00",
            "endTime": "15:00",
            "duration": 60,
            "priority": "must",
            "notes": "乘坐南海电铁前往难波"
          }
        ]
      }
    ],
    "createdAt": "2026-09-17T10:30:00Z",
    "updatedAt": "2026-09-17T12:00:00Z"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface TripDetail extends Trip {
  days: TripDay[];
}

interface TripDay {
  dayNumber: number;
  date: string;
  title: string;
  location: string;
  items: TripItem[];
}

interface TripItem {
  itemId: string;
  order: number;
  title: string;
  type: string;
  location: GeoLocation;
  startTime: string;
  endTime: string;
  duration: number; // 分钟
  priority: PriorityLevel;
  notes: string | null;
}

interface GeoLocation {
  lat: number;
  lng: number;
  address: string;
}

type PriorityLevel = 'must' | 'recommended' | 'optional' | 'excluded';
```

---

### 3.4 更新行程

**PUT** `/trips/{tripId}`

**请求头：** `Authorization: Bearer <accessToken>`

**请求体：**

```json
{
  "title": "2026年国庆日本关西深度游",
  "description": "更新：增加神户行程",
  "budget": 20000,
  "travelers": 3,
  "tags": ["日本", "关西", "文化", "美食", "温泉"]
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "行程更新成功",
  "data": {
    "tripId": "trip_x1y2z3a4b5c6",
    "title": "2026年国庆日本关西深度游",
    "description": "更新：增加神户行程",
    "destination": "日本关西",
    "startDate": "2026-10-01",
    "endDate": "2026-10-07",
    "budget": 20000,
    "currency": "CNY",
    "travelers": 3,
    "tags": ["日本", "关西", "文化", "美食", "温泉"],
    "coverImage": null,
    "status": "PLANNED",
    "ownerId": "usr_a1b2c3d4e5f6",
    "createdAt": "2026-09-17T10:30:00Z",
    "updatedAt": "2026-09-17T15:00:00Z"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface UpdateTripRequest {
  title?: string;
  description?: string;
  destination?: string;
  startDate?: string;
  endDate?: string;
  budget?: number;
  currency?: string;
  travelers?: number;
  tags?: string[];
  coverImage?: string | null;
}
```

---

### 3.5 删除行程

**DELETE** `/trips/{tripId}`

**请求头：** `Authorization: Bearer <accessToken>`

**响应示例：**

```json
{
  "code": 200,
  "message": "行程删除成功",
  "data": null,
  "timestamp": 1726550400000
}
```

---

### 3.6 触发行程规划

**POST** `/trips/{tripId}/plan`

**请求头：** `Authorization: Bearer <accessToken>`

**请求体：**

```json
{
  "requirements": "希望每天上午安排一个必去景点，下午自由活动，晚上体验当地美食。交通方式以地铁和步行为主。",
  "transportMode": "mixed",
  "intensity": "standard",
  "preferences": {
    "avoidCrowds": true,
    "preferLocalFood": true,
    "museumPriority": "recommended"
  }
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "规划任务已提交",
  "data": {
    "taskId": "task_m1n2o3p4q5r6",
    "tripId": "trip_x1y2z3a4b5c6",
    "status": "PENDING",
    "createdAt": "2026-09-17T10:30:00Z"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface PlanTripRequest {
  requirements: string;
  transportMode?: TransportMode;
  intensity?: IntensityLevel;
  preferences?: Record<string, any>;
}

interface PlanTask {
  taskId: string;
  tripId: string;
  status: TaskStatus;
  createdAt: string;
}

type TaskStatus = 'pending' | 'running' | 'completed' | 'failed' | 'dead_letter';
```

---

### 3.7 公开行程

**PUT** `/trips/{tripId}/public`

**请求头：** `Authorization: Bearer <accessToken>`

**请求体：**

```json
{
  "isPublic": true
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "行程已公开",
  "data": {
    "tripId": "trip_x1y2z3a4b5c6",
    "isPublic": true,
    "publicUrl": "https://trip.example.com/public/trip_x1y2z3a4b5c6"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface SetPublicRequest {
  isPublic: boolean;
}

interface SetPublicResponse {
  tripId: string;
  isPublic: boolean;
  publicUrl: string;
}
```

---

### 3.8 分享链接访问

**GET** `/trips/share/{shareToken}`

**无需认证**

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "tripId": "trip_x1y2z3a4b5c6",
    "title": "2026年国庆日本关西之旅",
    "description": "京都、大阪、奈良 7 天深度游",
    "destination": "日本关西",
    "startDate": "2026-10-01",
    "endDate": "2026-10-07",
    "travelers": 2,
    "ownerNickname": "三哥",
    "days": [...],
    "sharedAt": "2026-09-17T16:00:00Z",
    "expiresAt": "2026-10-31T23:59:59Z"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface SharedTripView {
  tripId: string;
  title: string;
  description: string;
  destination: string;
  startDate: string;
  endDate: string;
  travelers: number;
  ownerNickname: string;
  days: TripDay[];
  sharedAt: string;
  expiresAt: string | null;
}
```

---

### 3.9 版本列表

**GET** `/trips/{tripId}/versions`

**请求头：** `Authorization: Bearer <accessToken>`

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "versionId": "ver_a1b2c3d4",
      "tripId": "trip_x1y2z3a4b5c6",
      "versionNumber": 1,
      "label": "初始版本",
      "isCurrent": false,
      "createdAt": "2026-09-17T10:30:00Z"
    },
    {
      "versionId": "ver_e5f6g7h8",
      "tripId": "trip_x1y2z3a4b5c6",
      "versionNumber": 2,
      "label": "增加神户行程",
      "isCurrent": true,
      "createdAt": "2026-09-17T15:00:00Z"
    }
  ],
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface TripVersion {
  versionId: string;
  tripId: string;
  versionNumber: number;
  label: string;
  isCurrent: boolean;
  createdAt: string;
}
```

---

### 3.10 版本详情

**GET** `/trips/{tripId}/versions/{versionId}`

**请求头：** `Authorization: Bearer <accessToken>`

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "versionId": "ver_a1b2c3d4",
    "tripId": "trip_x1y2z3a4b5c6",
    "versionNumber": 1,
    "label": "初始版本",
    "isCurrent": false,
    "snapshot": {
      "title": "2026年国庆日本关西之旅",
      "days": [...]
    },
    "createdAt": "2026-09-17T10:30:00Z"
  },
  "timestamp": 1726550400000
}
```

---

### 3.11 创建新版本

**POST** `/trips/{tripId}/versions`

**请求头：** `Authorization: Bearer <accessToken>`

**请求体：**

```json
{
  "label": "调整第3天行程"
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "版本创建成功",
  "data": {
    "versionId": "ver_i9j0k1l2",
    "tripId": "trip_x1y2z3a4b5c6",
    "versionNumber": 3,
    "label": "调整第3天行程",
    "isCurrent": true,
    "createdAt": "2026-09-17T16:30:00Z"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface CreateVersionRequest {
  label: string;
}
```

---

### 3.12 回滚版本

**POST** `/trips/{tripId}/versions/{versionId}/rollback`

**请求头：** `Authorization: Bearer <accessToken>`

**响应示例：**

```json
{
  "code": 200,
  "message": "版本回滚成功",
  "data": {
    "versionId": "ver_a1b2c3d4",
    "tripId": "trip_x1y2z3a4b5c6",
    "versionNumber": 1,
    "label": "初始版本",
    "isCurrent": true,
    "createdAt": "2026-09-17T10:30:00Z"
  },
  "timestamp": 1726550400000
}
```

---

### 3.13 分支新建

**POST** `/trips/{tripId}/branches`

**请求头：** `Authorization: Bearer <accessToken>`

**请求体：**

```json
{
  "branchName": "尝试性方案",
  "description": "尝试不同的行程安排",
  "baseVersionId": "ver_a1b2c3d4"
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "分支创建成功",
  "data": {
    "branchId": "br_m3n4o5p6",
    "branchName": "尝试性方案",
    "description": "尝试不同的行程安排",
    "baseVersionId": "ver_a1b2c3d4",
    "createdAt": "2026-09-17T17:00:00Z"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface CreateBranchRequest {
  branchName: string;
  description?: string;
  baseVersionId: string;
}

interface Branch {
  branchId: string;
  branchName: string;
  description: string | null;
  baseVersionId: string;
  createdAt: string;
}
```

---

### 3.14 生成分享链接

**POST** `/trips/{tripId}/share`

**请求头：** `Authorization: Bearer <accessToken>`

**请求体：**

```json
{
  "expiresInDays": 30,
  "allowDownload": true,
  "password": null
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "分享链接生成成功",
  "data": {
    "shareToken": "sh_a1b2c3d4e5f6",
    "shareUrl": "https://trip.example.com/share/sh_a1b2c3d4e5f6",
    "expiresAt": "2026-10-17T17:00:00Z",
    "allowDownload": true,
    "hasPassword": false
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface CreateShareRequest {
  expiresInDays?: number; // 默认30天
  allowDownload?: boolean;
  password?: string | null;
}

interface ShareInfo {
  shareToken: string;
  shareUrl: string;
  expiresAt: string | null;
  allowDownload: boolean;
  hasPassword: boolean;
}
```

---

### 3.15 撤销分享

**DELETE** `/trips/{tripId}/share`

**请求头：** `Authorization: Bearer <accessToken>`

**响应示例：**

```json
{
  "code": 200,
  "message": "分享已撤销",
  "data": null,
  "timestamp": 1726550400000
}
```

---

### 3.16 获取分享信息

**GET** `/trips/{tripId}/share`

**请求头：** `Authorization: Bearer <accessToken>`

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "shareToken": "sh_a1b2c3d4e5f6",
    "shareUrl": "https://trip.example.com/share/sh_a1b2c3d4e5f6",
    "expiresAt": "2026-10-17T17:00:00Z",
    "allowDownload": true,
    "hasPassword": false,
    "viewCount": 42,
    "createdAt": "2026-09-17T17:00:00Z"
  },
  "timestamp": 1726550400000
}
```

---

### 3.17 导出行程

**GET** `/trips/{tripId}/export`

**请求头：** `Authorization: Bearer <accessToken>`

**查询参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| format | string | 否 | 导出格式：pdf / json / csv，默认 pdf |

**请求示例：**

```
GET /trips/trip_x1y2z3a4b5c6/export?format=pdf
```

**响应示例（PDF）：**

返回二进制流，Content-Type: `application/pdf`

**响应示例（JSON）：**

```json
{
  "code": 200,
  "message": "导出成功",
  "data": {
    "downloadUrl": "https://cdn.example.com/exports/trip_x1y2z3a4b5c6.pdf",
    "expiresAt": "2026-09-17T19:00:00Z"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
type ExportFormat = 'pdf' | 'json' | 'csv';

interface ExportResponse {
  downloadUrl: string;
  expiresAt: string;
}
```

---

## 4. 规划服务 Plan

> **服务端口**: 8083  
> **基础路径**: `/plan`

---

### 4.1 解析预览

**POST** `/plan/parse-preview`

**请求头：** `Authorization: Bearer <accessToken>`

**请求体：**

```json
{
  "tripId": "trip_x1y2z3a4b5c6",
  "inputText": "我想去日本京都玩5天，喜欢寺庙和美食，预算1万人民币，交通以地铁和步行为主",
  "transportMode": "mixed",
  "intensity": "standard"
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "解析完成",
  "data": {
    "parsedDestination": "日本京都",
    "parsedDays": 5,
    "parsedBudget": 10000,
    "parsedInterests": ["寺庙", "美食"],
    "parsedTransportMode": "mixed",
    "parsedIntensity": "standard",
    "suggestions": [
      "建议增加奈良作为一日游目的地",
      "推荐体验京都传统町屋住宿"
    ],
    "confidence": 0.92
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface ParsePreviewRequest {
  tripId: string;
  inputText: string;
  transportMode?: TransportMode;
  intensity?: IntensityLevel;
}

interface ParsePreviewResponse {
  parsedDestination: string;
  parsedDays: number | null;
  parsedBudget: number | null;
  parsedInterests: string[];
  parsedTransportMode: TransportMode;
  parsedIntensity: IntensityLevel;
  suggestions: string[];
  confidence: number; // 0-1
}
```

---

### 4.2 查询任务进度

**GET** `/plan/tasks/{taskId}/progress`

**请求头：** `Authorization: Bearer <accessToken>`

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "taskId": "task_m1n2o3p4q5r6",
    "tripId": "trip_x1y2z3a4b5c6",
    "status": "running",
    "currentStage": "SOLVE",
    "progress": 0.65,
    "stages": [
      { "stage": "PARSE_INPUT", "status": "completed", "progress": 1.0 },
      { "stage": "GEOCODE", "status": "completed", "progress": 1.0 },
      { "stage": "BUILD_MODEL", "status": "completed", "progress": 1.0 },
      { "stage": "SOLVE", "status": "running", "progress": 0.45 },
      { "stage": "ROUTE", "status": "pending", "progress": 0 },
      { "stage": "PERSIST", "status": "pending", "progress": 0 }
    ],
    "startedAt": "2026-09-17T10:30:00Z",
    "estimatedRemaining": 45,
    "updatedAt": "2026-09-17T10:32:00Z"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
type PlanStage = 'PARSE_INPUT' | 'GEOCODE' | 'BUILD_MODEL' | 'SOLVE' | 'ROUTE' | 'PERSIST';

interface PlanProgress {
  taskId: string;
  tripId: string;
  status: TaskStatus;
  currentStage: PlanStage;
  progress: number; // 0-1
  stages: StageProgress[];
  startedAt: string;
  estimatedRemaining: number; // 预估剩余秒数
  updatedAt: string;
}

interface StageProgress {
  stage: PlanStage;
  status: TaskStatus;
  progress: number;
}
```

---

### 4.3 获取行程任务列表

**GET** `/plan/tasks`

**请求头：** `Authorization: Bearer <accessToken>`

**查询参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| tripId | string | 否 | 按行程筛选 |
| status | string | 否 | 按状态筛选 |
| page | number | 否 | 页码 |
| size | number | 否 | 每页条数 |

**请求示例：**

```
GET /plan/tasks?tripId=trip_x1y2z3a4b5c6&status=completed
```

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "content": [
      {
        "taskId": "task_m1n2o3p4q5r6",
        "tripId": "trip_x1y2z3a4b5c6",
        "status": "completed",
        "currentStage": "PERSIST",
        "progress": 1.0,
        "createdAt": "2026-09-17T10:30:00Z",
        "completedAt": "2026-09-17T10:33:00Z"
      }
    ],
    "totalElements": 1,
    "totalPages": 1,
    "number": 0,
    "size": 20
  },
  "timestamp": 1726550400000
}
```

---

### 4.4 地理编码

**POST** `/plan/geocode`

**请求头：** `Authorization: Bearer <accessToken>`

**请求体：**

```json
{
  "address": "日本京都岚山天龙寺",
  "country": "JP"
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "编码成功",
  "data": {
    "lat": 35.0152,
    "lng": 135.6747,
    "formattedAddress": "〒616-8385 京都府京都市右京区嵯峨天龍寺芒ノ馬場町",
    "country": "JP",
    "city": "京都市",
    "district": "右京区"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface GeocodeRequest {
  address: string;
  country?: string;
}

interface GeocodeResponse {
  lat: number;
  lng: number;
  formattedAddress: string;
  country: string;
  city: string;
  district: string;
}
```

---

### 4.5 批量地理编码

**POST** `/plan/geocode/batch`

**请求头：** `Authorization: Bearer <accessToken>`

**请求体：**

```json
{
  "addresses": [
    { "id": "loc_1", "address": "京都岚山天龙寺" },
    { "id": "loc_2", "address": "大阪道顿堀" },
    { "id": "loc_3", "address": "奈良公园" }
  ],
  "country": "JP"
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "批量编码完成",
  "data": {
    "results": [
      {
        "id": "loc_1",
        "lat": 35.0152,
        "lng": 135.6747,
        "formattedAddress": "〒616-8385 京都府京都市右京区嵯峨天龍寺芒ノ馬場町",
        "success": true
      },
      {
        "id": "loc_2",
        "lat": 34.6687,
        "lng": 135.5013,
        "formattedAddress": "〒542-0071 大阪府大阪市中央区道頓堀",
        "success": true
      },
      {
        "id": "loc_3",
        "lat": 34.6851,
        "lng": 135.8430,
        "formattedAddress": "〒630-0811 奈良県奈良市雑司町",
        "success": true
      }
    ],
    "totalProcessed": 3,
    "totalSuccess": 3,
    "totalFailed": 0
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface BatchGeocodeRequest {
  addresses: Array<{ id: string; address: string }>;
  country?: string;
}

interface BatchGeocodeResponse {
  results: Array<GeocodeResult & { id: string; success: boolean }>;
  totalProcessed: number;
  totalSuccess: number;
  totalFailed: number;
}

interface GeocodeResult {
  lat: number;
  lng: number;
  formattedAddress: string;
}
```

---

### 4.6 逆地理编码

**POST** `/plan/reverse-geocode`

**请求头：** `Authorization: Bearer <accessToken>`

**请求体：**

```json
{
  "lat": 35.0152,
  "lng": 135.6747
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "逆编码成功",
  "data": {
    "formattedAddress": "〒616-8385 京都府京都市右京区嵯峨天龍寺芒ノ馬場町",
    "country": "日本",
    "countryCode": "JP",
    "city": "京都市",
    "district": "右京区",
    "neighborhood": "嵯峨天龍寺",
    "postcode": "616-8385"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface ReverseGeocodeRequest {
  lat: number;
  lng: number;
}

interface ReverseGeocodeResponse {
  formattedAddress: string;
  country: string;
  countryCode: string;
  city: string;
  district: string;
  neighborhood: string;
  postcode: string;
}
```

---

### 4.7 路线规划

**POST** `/plan/route`

**请求头：** `Authorization: Bearer <accessToken>`

**请求体：**

```json
{
  "origin": {
    "lat": 35.0152,
    "lng": 135.6747
  },
  "destination": {
    "lat": 34.6687,
    "lng": 135.5013
  },
  "transportMode": "transit",
  "waypoints": [
    {
      "lat": 34.9872,
      "lng": 135.7594
    }
  ],
  "departureTime": "2026-10-02T09:00:00+08:00",
  "alternatives": true
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "路线规划成功",
  "data": {
    "routes": [
      {
        "routeId": "route_a1b2c3",
        "summary": {
          "distance": 52800,
          "duration": 4500,
          "durationText": "1小时15分钟",
          "distanceText": "52.8公里"
        },
        "steps": [
          {
            "instruction": "从岚山天龙寺出发",
            "transportMode": "walk",
            "distance": 200,
            "duration": 120,
            "departureTime": "2026-10-02T09:00:00+08:00",
            "arrivalTime": "2026-10-02T09:02:00+08:00"
          },
          {
            "instruction": "乘坐JR嵯峨野线到京都站",
            "transportMode": "transit",
            "distance": 15000,
            "duration": 900,
            "departureTime": "2026-10-02T09:02:00+08:00",
            "arrivalTime": "2026-10-02T09:17:00+08:00",
            "transitDetails": {
              "line": "JR嵯峨野線",
              "departureStop": "嵯峨嵐山",
              "arrivalStop": "京都",
              "stops": 12
            }
          }
        ],
        "polyline": "encoded_polyline_string..."
      }
    ],
    "alternativeCount": 2,
    "queryTime": "2026-10-02T09:00:00+08:00"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface RouteRequest {
  origin: GeoLocation;
  destination: GeoLocation;
  transportMode: TransportMode;
  waypoints?: GeoLocation[];
  departureTime?: string;
  alternatives?: boolean;
}

interface RouteResponse {
  routes: Route[];
  alternativeCount: number;
  queryTime: string;
}

interface Route {
  routeId: string;
  summary: RouteSummary;
  steps: RouteStep[];
  polyline: string;
}

interface RouteSummary {
  distance: number; // 米
  duration: number; // 秒
  durationText: string;
  distanceText: string;
}

interface RouteStep {
  instruction: string;
  transportMode: TransportMode;
  distance: number;
  duration: number;
  departureTime: string;
  arrivalTime: string;
  transitDetails?: TransitDetails;
}

interface TransitDetails {
  line: string;
  departureStop: string;
  arrivalStop: string;
  stops: number;
}
```

---

## 5. 通知服务 Notification

> **服务端口**: 8085  
> **基础路径**: `/notifications`

---

### 5.1 通知列表

**GET** `/notifications`

**请求头：** `Authorization: Bearer <accessToken>`

**查询参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| page | number | 否 | 页码，默认 1 |
| size | number | 否 | 每页条数，默认 20 |
| type | string | 否 | 按通知类型筛选 |
| isRead | boolean | 否 | 按已读状态筛选 |

**请求示例：**

```
GET /notifications?page=1&size=20&type=COMPLETED
```

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "content": [
      {
        "notificationId": "ntf_a1b2c3d4",
        "type": "COMPLETED",
        "title": "行程规划完成",
        "content": "您的「日本关西之旅」规划已完成，请查看详情",
        "metadata": {
          "tripId": "trip_x1y2z3a4b5c6",
          "taskId": "task_m1n2o3p4q5r6"
        },
        "isRead": false,
        "createdAt": "2026-09-17T10:33:00Z"
      }
    ],
    "totalElements": 5,
    "totalPages": 1,
    "number": 0,
    "size": 20
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
type NotificationType = 'PROGRESS' | 'COMPLETED' | 'FAILED' | 'REPLAN' | 'SYSTEM' | 'SHARE';

interface Notification {
  notificationId: string;
  type: NotificationType;
  title: string;
  content: string;
  metadata: Record<string, any>;
  isRead: boolean;
  createdAt: string;
}
```

---

### 5.2 未读通知列表

**GET** `/notifications/unread`

**请求头：** `Authorization: Bearer <accessToken>`

**查询参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| page | number | 否 | 页码，默认 1 |
| size | number | 否 | 每页条数，默认 20 |

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "content": [
      {
        "notificationId": "ntf_e5f6g7h8",
        "type": "PROGRESS",
        "title": "行程规划中",
        "content": "您的「日本关西之旅」正在规划中，当前进度65%",
        "metadata": {
          "tripId": "trip_x1y2z3a4b5c6",
          "progress": 0.65
        },
        "isRead": false,
        "createdAt": "2026-09-17T10:32:00Z"
      }
    ],
    "totalElements": 1,
    "totalPages": 1,
    "number": 0,
    "size": 20
  },
  "timestamp": 1726550400000
}
```

---

### 5.3 未读通知数量

**GET** `/notifications/unread/count`

**请求头：** `Authorization: Bearer <accessToken>`

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "count": 3
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface UnreadCountResponse {
  count: number;
}
```

---

### 5.4 标记已读

**PUT** `/notifications/{notificationId}/read`

**请求头：** `Authorization: Bearer <accessToken>`

**响应示例：**

```json
{
  "code": 200,
  "message": "已标记为已读",
  "data": null,
  "timestamp": 1726550400000
}
```

---

### 5.5 全部已读

**PUT** `/notifications/read-all`

**请求头：** `Authorization: Bearer <accessToken>`

**响应示例：**

```json
{
  "code": 200,
  "message": "全部已标记为已读",
  "data": {
    "markedCount": 3
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface MarkAllReadResponse {
  markedCount: number;
}
```

---

### 5.6 清理通知

**DELETE** `/notifications/clean`

**请求头：** `Authorization: Bearer <accessToken>`

**查询参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| type | string | 否 | 清理指定类型的通知 |
| before | string | 否 | 清理此时间之前的通知 (ISO 8601) |
| readOnly | boolean | 否 | 仅清理已读通知 |

**请求示例：**

```
DELETE /notifications/clean?readOnly=true
```

**响应示例：**

```json
{
  "code": 200,
  "message": "清理完成",
  "data": {
    "deletedCount": 12
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface CleanNotificationsQuery {
  type?: NotificationType;
  before?: string;
  readOnly?: boolean;
}

interface CleanNotificationsResponse {
  deletedCount: number;
}
```

---

## 6. WebSocket 接入

### 6.1 连接地址

```
ws://localhost:8080/ws
```

### 6.2 协议

支持以下子协议（通过 `Sec-WebSocket-Protocol` 头传递）：

```
v1规划进度, v1通知推送
```

### 6.3 认证

连接时通过查询参数传递 JWT Token：

```
ws://localhost:8080/ws?token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

或者通过第一条消息认证：

```json
{
  "type": "auth",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

### 6.4 订阅主题

连接成功后，发送订阅消息：

```json
{
  "type": "subscribe",
  "topics": [
    "trip.trip_x1y2z3a4b5c6.progress",
    "user.usr_a1b2c3d4e5f6.notifications"
  ]
}
```

**订阅消息格式：**

```json
{
  "type": "subscribed",
  "topics": [
    "trip.trip_x1y2z3a4b5c6.progress",
    "user.usr_a1b2c3d4e5f6.notifications"
  ]
}
```

### 6.5 ProgressMessage 数据格式

**规划进度推送：**

```json
{
  "type": "progress",
  "topic": "trip.trip_x1y2z3a4b5c6.progress",
  "data": {
    "taskId": "task_m1n2o3p4q5r6",
    "tripId": "trip_x1y2z3a4b5c6",
    "status": "running",
    "currentStage": "SOLVE",
    "progress": 0.65,
    "message": "正在求解最优路线...",
    "stages": [
      { "stage": "PARSE_INPUT", "status": "completed", "progress": 1.0 },
      { "stage": "GEOCODE", "status": "completed", "progress": 1.0 },
      { "stage": "BUILD_MODEL", "status": "completed", "progress": 1.0 },
      { "stage": "SOLVE", "status": "running", "progress": 0.45 },
      { "stage": "ROUTE", "status": "pending", "progress": 0 },
      { "stage": "PERSIST", "status": "pending", "progress": 0 }
    ],
    "updatedAt": "2026-09-17T10:32:00Z"
  },
  "timestamp": 1726550400000
}
```

**规划完成推送：**

```json
{
  "type": "plan_completed",
  "topic": "trip.trip_x1y2z3a4b5c6.progress",
  "data": {
    "taskId": "task_m1n2o3p4q5r6",
    "tripId": "trip_x1y2z3a4b5c6",
    "status": "completed",
    "message": "行程规划已完成",
    "result": {
      "daysCount": 7,
      "totalActivities": 35,
      "estimatedCost": 12500
    },
    "completedAt": "2026-09-17T10:33:00Z"
  },
  "timestamp": 1726550400000
}
```

**通知推送：**

```json
{
  "type": "notification",
  "topic": "user.usr_a1b2c3d4e5f6.notifications",
  "data": {
    "notificationId": "ntf_i9j0k1l2",
    "type": "COMPLETED",
    "title": "行程规划完成",
    "content": "您的「日本关西之旅」规划已完成",
    "metadata": {
      "tripId": "trip_x1y2z3a4b5c6"
    },
    "createdAt": "2026-09-17T10:33:00Z"
  },
  "timestamp": 1726550400000
}
```

**TypeScript 类型：**

```typescript
interface WebSocketMessage {
  type: string;
  topic?: string;
  data?: any;
  timestamp?: number;
}

interface ProgressMessage {
  taskId: string;
  tripId: string;
  status: TaskStatus;
  currentStage: PlanStage;
  progress: number;
  message: string;
  stages: StageProgress[];
  updatedAt: string;
}

interface PlanCompletedMessage {
  taskId: string;
  tripId: string;
  status: 'completed';
  message: string;
  result: {
    daysCount: number;
    totalActivities: number;
    estimatedCost: number;
  };
  completedAt: string;
}

interface NotificationPush {
  notificationId: string;
  type: NotificationType;
  title: string;
  content: string;
  metadata: Record<string, any>;
  createdAt: string;
}
```

### 6.6 心跳保活

客户端应每 30 秒发送心跳：

```json
{
  "type": "ping"
}
```

服务端响应：

```json
{
  "type": "pong"
}
```

---

## 7. 前端典型使用流程

### 流程一：注册登录

```typescript
// 1. 注册
const registerRes = await fetch('/auth/register', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    username: 'zhangsan',
    email: 'zhangsan@example.com',
    password: 'Abc@1234',
    nickname: '张三'
  })
});

// 2. 登录
const loginRes = await fetch('/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    email: 'zhangsan@example.com',
    password: 'Abc@1234'
  })
});
const { accessToken, refreshToken, user } = loginRes.data.data;

// 3. 存储 Token
localStorage.setItem('accessToken', accessToken);
localStorage.setItem('refreshToken', refreshToken);
```

### 流程二：创建行程

```typescript
const createTripRes = await fetch('/trips', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${localStorage.getItem('accessToken')}`
  },
  body: JSON.stringify({
    title: '2026年国庆日本关西之旅',
    destination: '日本关西',
    startDate: '2026-10-01',
    endDate: '2026-10-07',
    budget: 15000,
    travelers: 2,
    tags: ['日本', '关西']
  })
});
const { tripId } = createTripRes.data.data;
```

### 流程三：查看规划进度

```typescript
// 方式一：轮询
const pollProgress = async (taskId: string) => {
  const interval = setInterval(async () => {
    const res = await fetch(`/plan/tasks/${taskId}/progress`, {
      headers: { 'Authorization': `Bearer ${accessToken}` }
    });
    const { status, progress, currentStage } = res.data.data;

    console.log(`阶段: ${currentStage}, 进度: ${(progress * 100).toFixed(0)}%`);

    if (status === 'completed' || status === 'failed') {
      clearInterval(interval);
    }
  }, 3000);
};

// 方式二：WebSocket（推荐）
const ws = new WebSocket('ws://localhost:8080/ws?token=' + accessToken);
ws.onopen = () => {
  ws.send(JSON.stringify({
    type: 'subscribe',
    topics: [`trip.${tripId}.progress`]
  }));
};
ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  if (msg.type === 'progress') {
    console.log(`规划进度: ${(msg.data.progress * 100).toFixed(0)}%`);
  } else if (msg.type === 'plan_completed') {
    console.log('规划完成！');
  }
};
```

### 流程四：查看行程详情

```typescript
const tripDetailRes = await fetch(`/trips/${tripId}`, {
  headers: { 'Authorization': `Bearer ${accessToken}` }
});
const trip = tripDetailRes.data.data;

// 渲染行程卡片
trip.days.forEach(day => {
  console.log(`第${day.dayNumber}天 - ${day.title}`);
  day.items.forEach(item => {
    console.log(`  ${item.startTime} ${item.title} [${item.priority}]`);
  });
});
```

### 流程五：分享导出

```typescript
// 1. 生成分享链接
const shareRes = await fetch(`/trips/${tripId}/share`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${accessToken}`
  },
  body: JSON.stringify({ expiresInDays: 30 })
});
const { shareUrl } = shareRes.data.data;
console.log(`分享链接: ${shareUrl}`);

// 2. 导出 PDF
const exportRes = await fetch(`/trips/${tripId}/export?format=pdf`, {
  headers: { 'Authorization': `Bearer ${accessToken}` }
});
const { downloadUrl } = exportRes.data.data;
window.open(downloadUrl);
```

---

## 8. TypeScript 类型参考

### 通用类型

```typescript
/** 统一响应格式 */
interface ApiResponse<T = any> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
  errors?: Array<{ field: string; message: string }>;
}

/** 分页响应 */
interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/** 分页请求参数 */
interface PageRequest {
  page?: number;
  size?: number;
  sort?: string;
}

/** 地理位置 */
interface GeoLocation {
  lat: number;
  lng: number;
  address: string;
}

/** 交通方式 */
type TransportMode = 'walk' | 'transit' | 'drive' | 'bike' | 'mixed';

/** 优先级 */
type PriorityLevel = 'must' | 'recommended' | 'optional' | 'excluded';

/** 行程强度 */
type IntensityLevel = 'relaxed' | 'standard' | 'packed';

/** 行程状态 */
type TripStatus = 'DRAFT' | 'PLANNING' | 'PLANNED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

/** 任务状态 */
type TaskStatus = 'pending' | 'running' | 'completed' | 'failed' | 'dead_letter';

/** 规划阶段 */
type PlanStage = 'PARSE_INPUT' | 'GEOCODE' | 'BUILD_MODEL' | 'SOLVE' | 'ROUTE' | 'PERSIST';

/** 通知类型 */
type NotificationType = 'PROGRESS' | 'COMPLETED' | 'FAILED' | 'REPLAN' | 'SYSTEM' | 'SHARE';

/** 导出格式 */
type ExportFormat = 'pdf' | 'json' | 'csv';
```

### Auth 服务类型

```typescript
interface RegisterRequest {
  username: string;
  email: string;
  password: string;
  nickname?: string;
}

interface RegisterResponse {
  userId: string;
  username: string;
  email: string;
  nickname: string;
  createdAt: string;
}

interface LoginRequest {
  email: string;
  password: string;
}

interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  tokenType: string;
  user: UserInfo;
}

interface UserInfo {
  userId: string;
  username: string;
  email: string;
  nickname: string;
  avatar: string | null;
}

interface RefreshTokenRequest {
  refreshToken: string;
}

interface RefreshTokenResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  tokenType: string;
}

interface UserProfile {
  userId: string;
  username: string;
  email: string;
  nickname: string;
  avatar: string | null;
  phone: string | null;
  createdAt: string;
  updatedAt: string;
}

interface UpdateProfileRequest {
  nickname?: string;
  phone?: string;
  avatar?: string;
}

interface ChangePasswordRequest {
  oldPassword: string;
  newPassword: string;
}

interface UserPreferences {
  transportMode: TransportMode;
  intensity: IntensityLevel;
  budget: 'low' | 'medium' | 'high';
  language: string;
  currency: string;
  notifications: NotificationSettings;
  defaultCity: string;
}

interface NotificationSettings {
  email: boolean;
  push: boolean;
  sms: boolean;
}

interface UpdatePreferencesRequest {
  transportMode?: TransportMode;
  intensity?: IntensityLevel;
  budget?: 'low' | 'medium' | 'high';
  language?: string;
  currency?: string;
  notifications?: Partial<NotificationSettings>;
  defaultCity?: string;
}
```

### Trip 服务类型

```typescript
interface CreateTripRequest {
  title: string;
  description?: string;
  destination: string;
  startDate: string;
  endDate: string;
  budget?: number;
  currency?: string;
  travelers?: number;
  tags?: string[];
  coverImage?: string | null;
}

interface Trip {
  tripId: string;
  title: string;
  description: string | null;
  destination: string;
  startDate: string;
  endDate: string;
  budget: number | null;
  currency: string;
  travelers: number;
  tags: string[];
  coverImage: string | null;
  status: TripStatus;
  ownerId: string;
  createdAt: string;
  updatedAt: string;
}

interface TripDetail extends Trip {
  days: TripDay[];
}

interface TripDay {
  dayNumber: number;
  date: string;
  title: string;
  location: string;
  items: TripItem[];
}

interface TripItem {
  itemId: string;
  order: number;
  title: string;
  type: string;
  location: GeoLocation;
  startTime: string;
  endTime: string;
  duration: number;
  priority: PriorityLevel;
  notes: string | null;
}

interface TripListQuery extends PageRequest {
  status?: TripStatus;
  keyword?: string;
  tag?: string;
  startDateFrom?: string;
  startDateTo?: string;
}

interface TripSummary {
  tripId: string;
  title: string;
  destination: string;
  startDate: string;
  endDate: string;
  status: TripStatus;
  coverImage: string | null;
  daysCount: number;
  createdAt: string;
}

interface UpdateTripRequest {
  title?: string;
  description?: string;
  destination?: string;
  startDate?: string;
  endDate?: string;
  budget?: number;
  currency?: string;
  travelers?: number;
  tags?: string[];
  coverImage?: string | null;
}

interface PlanTripRequest {
  requirements: string;
  transportMode?: TransportMode;
  intensity?: IntensityLevel;
  preferences?: Record<string, any>;
}

interface PlanTask {
  taskId: string;
  tripId: string;
  status: TaskStatus;
  createdAt: string;
}

interface SetPublicRequest {
  isPublic: boolean;
}

interface SetPublicResponse {
  tripId: string;
  isPublic: boolean;
  publicUrl: string;
}

interface SharedTripView {
  tripId: string;
  title: string;
  description: string;
  destination: string;
  startDate: string;
  endDate: string;
  travelers: number;
  ownerNickname: string;
  days: TripDay[];
  sharedAt: string;
  expiresAt: string | null;
}

interface TripVersion {
  versionId: string;
  tripId: string;
  versionNumber: number;
  label: string;
  isCurrent: boolean;
  createdAt: string;
}

interface CreateVersionRequest {
  label: string;
}

interface CreateBranchRequest {
  branchName: string;
  description?: string;
  baseVersionId: string;
}

interface Branch {
  branchId: string;
  branchName: string;
  description: string | null;
  baseVersionId: string;
  createdAt: string;
}

interface CreateShareRequest {
  expiresInDays?: number;
  allowDownload?: boolean;
  password?: string | null;
}

interface ShareInfo {
  shareToken: string;
  shareUrl: string;
  expiresAt: string | null;
  allowDownload: boolean;
  hasPassword: boolean;
}

interface ExportResponse {
  downloadUrl: string;
  expiresAt: string;
}
```

### Plan 服务类型

```typescript
interface ParsePreviewRequest {
  tripId: string;
  inputText: string;
  transportMode?: TransportMode;
  intensity?: IntensityLevel;
}

interface ParsePreviewResponse {
  parsedDestination: string;
  parsedDays: number | null;
  parsedBudget: number | null;
  parsedInterests: string[];
  parsedTransportMode: TransportMode;
  parsedIntensity: IntensityLevel;
  suggestions: string[];
  confidence: number;
}

interface PlanProgress {
  taskId: string;
  tripId: string;
  status: TaskStatus;
  currentStage: PlanStage;
  progress: number;
  stages: StageProgress[];
  startedAt: string;
  estimatedRemaining: number;
  updatedAt: string;
}

interface StageProgress {
  stage: PlanStage;
  status: TaskStatus;
  progress: number;
}

interface GeocodeRequest {
  address: string;
  country?: string;
}

interface GeocodeResponse {
  lat: number;
  lng: number;
  formattedAddress: string;
  country: string;
  city: string;
  district: string;
}

interface BatchGeocodeRequest {
  addresses: Array<{ id: string; address: string }>;
  country?: string;
}

interface BatchGeocodeResponse {
  results: Array<GeocodeResult & { id: string; success: boolean }>;
  totalProcessed: number;
  totalSuccess: number;
  totalFailed: number;
}

interface ReverseGeocodeRequest {
  lat: number;
  lng: number;
}

interface ReverseGeocodeResponse {
  formattedAddress: string;
  country: string;
  countryCode: string;
  city: string;
  district: string;
  neighborhood: string;
  postcode: string;
}

interface RouteRequest {
  origin: GeoLocation;
  destination: GeoLocation;
  transportMode: TransportMode;
  waypoints?: GeoLocation[];
  departureTime?: string;
  alternatives?: boolean;
}

interface RouteResponse {
  routes: Route[];
  alternativeCount: number;
  queryTime: string;
}

interface Route {
  routeId: string;
  summary: RouteSummary;
  steps: RouteStep[];
  polyline: string;
}

interface RouteSummary {
  distance: number;
  duration: number;
  durationText: string;
  distanceText: string;
}

interface RouteStep {
  instruction: string;
  transportMode: TransportMode;
  distance: number;
  duration: number;
  departureTime: string;
  arrivalTime: string;
  transitDetails?: TransitDetails;
}

interface TransitDetails {
  line: string;
  departureStop: string;
  arrivalStop: string;
  stops: number;
}
```

### Notification 服务类型

```typescript
interface Notification {
  notificationId: string;
  type: NotificationType;
  title: string;
  content: string;
  metadata: Record<string, any>;
  isRead: boolean;
  createdAt: string;
}

interface UnreadCountResponse {
  count: number;
}

interface MarkAllReadResponse {
  markedCount: number;
}

interface CleanNotificationsQuery {
  type?: NotificationType;
  before?: string;
  readOnly?: boolean;
}

interface CleanNotificationsResponse {
  deletedCount: number;
}
```

### WebSocket 类型

```typescript
interface WebSocketMessage {
  type: string;
  topic?: string;
  data?: any;
  timestamp?: number;
}

interface ProgressMessage {
  taskId: string;
  tripId: string;
  status: TaskStatus;
  currentStage: PlanStage;
  progress: number;
  message: string;
  stages: StageProgress[];
  updatedAt: string;
}

interface PlanCompletedMessage {
  taskId: string;
  tripId: string;
  status: 'completed';
  message: string;
  result: {
    daysCount: number;
    totalActivities: number;
    estimatedCost: number;
  };
  completedAt: string;
}

interface NotificationPush {
  notificationId: string;
  type: NotificationType;
  title: string;
  content: string;
  metadata: Record<string, any>;
  createdAt: string;
}
```

---

## 附录：常见问题

### Token 过期处理

```typescript
// 拦截器示例
api.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (error.response?.status === 401) {
      const refreshToken = localStorage.getItem('refreshToken');
      if (refreshToken) {
        try {
          const res = await fetch('/auth/refresh', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ refreshToken })
          });
          const { accessToken, refreshToken: newRefreshToken } = res.data.data;
          localStorage.setItem('accessToken', accessToken);
          localStorage.setItem('refreshToken', newRefreshToken);
          // 重试原请求
          error.config.headers['Authorization'] = `Bearer ${accessToken}`;
          return api(error.config);
        } catch (e) {
          // 刷新失败，跳转登录
          localStorage.clear();
          window.location.href = '/login';
        }
      }
    }
    return Promise.reject(error);
  }
);
```

### WebSocket 重连机制

```typescript
class WSClient {
  private ws: WebSocket | null = null;
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 5;

  connect(token: string) {
    this.ws = new WebSocket(`ws://localhost:8080/ws?token=${token}`);

    this.ws.onclose = () => {
      if (this.reconnectAttempts < this.maxReconnectAttempts) {
        setTimeout(() => {
          this.reconnectAttempts++;
          this.connect(token);
        }, Math.pow(2, this.reconnectAttempts) * 1000);
      }
    };

    this.ws.onopen = () => {
      this.reconnectAttempts = 0;
      // 心跳
      setInterval(() => {
        this.ws?.send(JSON.stringify({ type: 'ping' }));
      }, 30000);
    };
  }
}
```

---

> **文档维护**: 请在 API 变更时同步更新此文档
