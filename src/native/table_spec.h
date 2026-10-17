#pragma once
#include <cstdint>
// Generated from exact SYSTEM library. Value word offsets, count1.
// Profiles0..3 and SpatialAudio8 only. profile=-1 is explicit GLOBAL Common.
// Numeric target values are original defaults; neutral-on must preserve
// current numeric values and modify only records where is_flag==true.
// Common edits require official Spatializer0x10c after profile reload.
// Signed fields are raw firmware integers; no dB-unit assertion.
namespace dcl_spec {
struct Record { std::uint64_t file_offset; std::uint32_t parameter_id; std::int32_t original; std::int32_t target; int profile; const char* key; std::int32_t minimum; std::int32_t maximum; bool is_flag; const char* name; };
inline constexpr const char* kFingerprint="samsung/dm1qzcx/dm1q:16/BP4A.251205.006/S9110ZCS8FZF5:user/release-keys";
inline constexpr const char* kLibraryPath="/system/lib64/libspatializerparamstorage.so";
inline constexpr const char* kLibrarySha256="649C9F11CE3D2682C877A20272DD924D65C83E5500100596880D4CCEFE89A1CA";
inline constexpr const char* kLibraryBuildId="cecc1ff0f9ae72312c0a791a2d575605";
inline constexpr std::uint64_t kLibrarySize=27584;
inline constexpr Record kRecords[]={
  {0x16ecULL,0x6563736dU,1,0,0,"mi-surround-compressor-steering-enable",0,1,true,"profile0_Dynamic_msce"},
  {0x16fcULL,0x656c646dU,1,0,0,"mi-dv-leveler-steering-enable",0,1,true,"profile0_Dynamic_mdle"},
  {0x170cULL,0x6565696dU,0,0,0,"mi-ieq-steering-enable",0,1,true,"profile0_Dynamic_miee"},
  {0x171cULL,0x6565646dU,1,0,0,"mi-dialog-enhancer-steering-enable",0,1,true,"profile0_Dynamic_mdee"},
  {0x172cULL,0x6e6f6569U,0,0,0,"ieq-enable",0,1,true,"profile0_Dynamic_ieon"},
  {0x173cULL,0x6e6f6564U,1,0,0,"dialog-enhancer-enable",0,1,true,"profile0_Dynamic_deon"},
  {0x174cULL,0x656c7664U,1,0,0,"volume-leveler-enable",0,1,true,"profile0_Dynamic_dvle"},
  {0x190cULL,0x6563736dU,0,0,1,"mi-surround-compressor-steering-enable",0,1,true,"profile1_Movie_msce"},
  {0x191cULL,0x656c646dU,0,0,1,"mi-dv-leveler-steering-enable",0,1,true,"profile1_Movie_mdle"},
  {0x192cULL,0x6565696dU,0,0,1,"mi-ieq-steering-enable",0,1,true,"profile1_Movie_miee"},
  {0x193cULL,0x6565646dU,1,0,1,"mi-dialog-enhancer-steering-enable",0,1,true,"profile1_Movie_mdee"},
  {0x194cULL,0x6e6f6569U,0,0,1,"ieq-enable",0,1,true,"profile1_Movie_ieon"},
  {0x195cULL,0x6e6f6564U,1,0,1,"dialog-enhancer-enable",0,1,true,"profile1_Movie_deon"},
  {0x196cULL,0x656c7664U,1,0,1,"volume-leveler-enable",0,1,true,"profile1_Movie_dvle"},
  {0x1b2cULL,0x6563736dU,0,0,2,"mi-surround-compressor-steering-enable",0,1,true,"profile2_Music_msce"},
  {0x1b3cULL,0x656c646dU,0,0,2,"mi-dv-leveler-steering-enable",0,1,true,"profile2_Music_mdle"},
  {0x1b4cULL,0x6565696dU,0,0,2,"mi-ieq-steering-enable",0,1,true,"profile2_Music_miee"},
  {0x1b5cULL,0x6565646dU,0,0,2,"mi-dialog-enhancer-steering-enable",0,1,true,"profile2_Music_mdee"},
  {0x1b6cULL,0x6e6f6569U,1,0,2,"ieq-enable",0,1,true,"profile2_Music_ieon"},
  {0x1b7cULL,0x6e6f6564U,0,0,2,"dialog-enhancer-enable",0,1,true,"profile2_Music_deon"},
  {0x1b8cULL,0x656c7664U,1,0,2,"volume-leveler-enable",0,1,true,"profile2_Music_dvle"},
  {0x1d4cULL,0x6563736dU,0,0,3,"mi-surround-compressor-steering-enable",0,1,true,"profile3_Voice_msce"},
  {0x1d5cULL,0x656c646dU,0,0,3,"mi-dv-leveler-steering-enable",0,1,true,"profile3_Voice_mdle"},
  {0x1d6cULL,0x6565696dU,0,0,3,"mi-ieq-steering-enable",0,1,true,"profile3_Voice_miee"},
  {0x1d7cULL,0x6565646dU,0,0,3,"mi-dialog-enhancer-steering-enable",0,1,true,"profile3_Voice_mdee"},
  {0x1d8cULL,0x6e6f6569U,0,0,3,"ieq-enable",0,1,true,"profile3_Voice_ieon"},
  {0x1d9cULL,0x6e6f6564U,0,0,3,"dialog-enhancer-enable",0,1,true,"profile3_Voice_deon"},
  {0x1dacULL,0x656c7664U,1,0,3,"volume-leveler-enable",0,1,true,"profile3_Voice_dvle"},
  {0x218cULL,0x6563736dU,0,0,8,"mi-surround-compressor-steering-enable",0,1,true,"profile8_SpatialAudio_msce"},
  {0x219cULL,0x656c646dU,0,0,8,"mi-dv-leveler-steering-enable",0,1,true,"profile8_SpatialAudio_mdle"},
  {0x21acULL,0x6565696dU,0,0,8,"mi-ieq-steering-enable",0,1,true,"profile8_SpatialAudio_miee"},
  {0x21bcULL,0x6565646dU,1,0,8,"mi-dialog-enhancer-steering-enable",0,1,true,"profile8_SpatialAudio_mdee"},
  {0x21ccULL,0x6e6f6569U,0,0,8,"ieq-enable",0,1,true,"profile8_SpatialAudio_ieon"},
  {0x21dcULL,0x6e6f6564U,1,0,8,"dialog-enhancer-enable",0,1,true,"profile8_SpatialAudio_deon"},
  {0x21ecULL,0x656c7664U,1,0,8,"volume-leveler-enable",0,1,true,"profile8_SpatialAudio_dvle"},
  {0x176cULL,0x616c7664U,0,0,0,"volume-leveler-amount",0,9,false,"profile0_dvla"},
  {0x198cULL,0x616c7664U,0,0,1,"volume-leveler-amount",0,9,false,"profile1_dvla"},
  {0x1bacULL,0x616c7664U,0,0,2,"volume-leveler-amount",0,9,false,"profile2_dvla"},
  {0x1dccULL,0x616c7664U,0,0,3,"volume-leveler-amount",0,9,false,"profile3_dvla"},
  {0x220cULL,0x616c7664U,0,0,8,"volume-leveler-amount",0,9,false,"profile8_dvla"},
  {0x25dcULL,0x00616564U,4,4,-1,"dialog-enhancer-amount",0,9,false,"Common_dea"},
  {0x261cULL,0x696c7664U,-256,-256,-1,"volume-leveler-in-target",-640,0,false,"Common_dvli"},
  {0x262cULL,0x6f6c7664U,-224,-224,-1,"volume-leveler-out-target",-640,0,false,"Common_dvlo"},
  {0x260cULL,0x00626d76U,0,0,-1,"volume-max-boost",0,192,false,"Common_vmb"},
};
inline constexpr bool kReady=true;
} // namespace dcl_spec
