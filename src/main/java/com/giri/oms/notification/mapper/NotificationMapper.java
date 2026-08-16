package com.giri.oms.notification.mapper;

import com.giri.oms.notification.dto.NotificationResponse;
import com.giri.oms.notification.entity.Notification;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface NotificationMapper {

    NotificationResponse mapToNotificationResponse(Notification notification);
}
