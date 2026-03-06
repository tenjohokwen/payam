package com.softropic.payam.security.core.mapper;



import com.softropic.payam.common.util.PhoneNumberUtil;
import com.softropic.payam.common.validation.PhoneNumber;
import com.softropic.payam.security.api.dto.AddressDto;
import com.softropic.payam.security.domain.Address;
import com.softropic.payam.security.exposed.UserDto;
import com.softropic.payam.security.domain.Authority;
import com.softropic.payam.security.domain.User;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.Persistence;


@Mapper(componentModel = "spring", imports = {org.apache.commons.lang3.StringUtils.class})
public interface UserMapper {
    @Mapping(source = "dateOfBirth", target = "dob")
    @Mapping(target = "authorities", ignore = true)
    @Mapping(target = "address", ignore = true)
    @Mapping(target = "login", expression = "java(StringUtils.lowerCase(user.getLogin()))")
    @Mapping(target = "email", expression = "java(StringUtils.lowerCase(user.getEmail()))")
    UserDto toUserDto(User user);

    @Mapping(source = "dob", target = "dateOfBirth")
    User toUser(UserDto userDto);

    default Set<String> toAuthStrings(Set<Authority> auths) {
        return auths.stream().map(Authority::getName).collect(Collectors.toSet());
    }

    @AfterMapping
    default void mapAuthToString(User user, @MappingTarget UserDto userDto) {
        if(Persistence.getPersistenceUtil().isLoaded(user, "authorities")) {
            final Set<String> auths = user.getAuthorities()
                                            .stream()
                                            .map(Authority::getName)
                                            .collect(Collectors.toSet());
            userDto.setAuthorities(auths);
        }
    }

    @AfterMapping
    default void mapAddress(User user, @MappingTarget UserDto userDto) {
        if (Persistence.getPersistenceUtil().isLoaded(user, "addresses")
                && user.getAddresses() != null && !user.getAddresses().isEmpty()) {
            userDto.setAddress(addressToAddressDto(user.getAddresses().iterator().next()));
        }
    }

    AddressDto addressToAddressDto(Address address);

    /**
     * The method default Set<String> toAuthStrings(Set<Authority> auths) is not added so as to avoid lazyInitializationException
     * @param auths
     * @return
     */
    default Set<Authority> toAuth(Set<String> auths) {
        return auths.stream().map(Authority::new).collect(Collectors.toSet());
    }

    /**
     * Maps PhoneNumber entity to String (for toUserDto).
     */
    default String phoneNumberToString(PhoneNumber phoneNumber) {
        return phoneNumber != null ? phoneNumber.getPhone() : null;
    }

    default PhoneNumber stringToPhoneNumber(String phone) {
        return PhoneNumberUtil.fromString(phone);
    }
}
